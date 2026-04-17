package com.drone.server.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.drone.pojo.dto.WsEnvelope;
import com.drone.server.ws.handler.WsCommandAckResult;
import com.drone.server.ws.handler.WsCommandResponseHandler;
import com.drone.server.ws.handler.WsMessageHandler;
import com.drone.server.ws.handler.WsMessageRouter;
import com.drone.server.ws.service.WsMessageService;
import com.drone.service.AppWebSocketService;
import com.drone.service.LiveSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.drone.server.exception.ApiErrorCode;
import com.drone.server.ws.service.LiveWebSessionProvider;
import com.drone.server.ws.service.LiveWebSessionService;

@Component
@Slf4j
public class DroneWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private LiveSessionService liveSessionService;

    @Autowired
    private WsMessageRouter messageRouter;

    @Autowired
    private WsMessageService messageService;

    @Autowired
    private WsCommandResponseHandler commandResponseHandler;

    @Autowired
    private LiveWebSessionService liveWebSessionService;

    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final long TIMEOUT = 60000;
    private static final long HEARTBEAT_INTERVAL = 30000;

    public DroneWebSocketHandler() {
        scheduler.scheduleAtFixedRate(this::checkSessions, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public Map<String, SessionInfo> getSessions() {
        return sessions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String deviceId = getDeviceIdFromUri(session);
        if (deviceId == null || deviceId.isBlank()) {
            session.close(CloseStatus.BAD_DATA);
            log.info("WebSocket 连接缺少 deviceId，已拒绝");
            return;
        }

        if (!appWebSocketService.isPending(deviceId)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            log.info("设备 {} 未申请连接，拒绝连接", deviceId);
            return;
        }

        session.getAttributes().put("deviceId", deviceId);
        sessions.put(deviceId, new SessionInfo(session, System.currentTimeMillis()));
        appWebSocketService.markAsConnected(deviceId);
        log.info("设备 {} 已连接", deviceId);

        WsEnvelope envelope = new WsEnvelope();
        envelope.setType("event");
        envelope.setName("CONNECT_SUCCESS");
        envelope.setDeviceId(deviceId);
        envelope.setTimestamp(System.currentTimeMillis());
        envelope.setSuccess(true);
        envelope.setMessage("连接成功");
        messageService.send(session, envelope);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        updateActiveTime(session);

        try {

            JSONObject json = JSON.parseObject(payload);
            if (json == null) {
                messageService.sendError(session, ApiErrorCode.INVALID_MESSAGE, "消息体不能为空");
                return;
            }

            String type = json.getString("type");
            if (type == null || type.isBlank()) {
                messageService.sendError(session, ApiErrorCode.INVALID_MESSAGE, "消息缺少 type");
                return;
            }

            messageRouter.route(json, session);
        } catch (Exception e) {
            log.warn("处理 App WebSocket 消息失败: {}", e.getMessage());
            messageService.sendError(session, ApiErrorCode.INVALID_MESSAGE, "无法解析 WebSocket 消息");
        }
    }

    public WsCommandAckResult sendStartLiveCommand(String deviceId, String roomId, String userId, String userSig, long ackTimeoutMillis, long startingTtlMillis) {
        String requestId = UUID.randomUUID().toString();

        WsEnvelope payload = new WsEnvelope();
        payload.setId(requestId);
        payload.setType("command");
        payload.setName("START_LIVE");
        payload.setDeviceId(deviceId);
        payload.setTimestamp(System.currentTimeMillis());

        Map<String, Object> data = new ConcurrentHashMap<>();
        data.put("roomId", roomId);
        data.put("userId", userId);
        data.put("userSig", userSig);
        payload.setData(data);

        CompletableFuture<WsCommandAckResult> future = new CompletableFuture<>();
        WsCommandResponseHandler.PendingCommand pendingCommand = new WsCommandResponseHandler.PendingCommand(
                deviceId,
                "START_LIVE",
                roomId,
                System.currentTimeMillis() + startingTtlMillis,
                future
        );
        commandResponseHandler.addPendingCommand(requestId, pendingCommand);
        liveSessionService.markStarting(deviceId, roomId, requestId, startingTtlMillis);

        if (!sendMessage(deviceId, JSON.toJSONString(payload))) {
            commandResponseHandler.removePendingCommand(requestId);
            liveSessionService.markFailed(deviceId);
            WsCommandAckResult failed = new WsCommandAckResult();
            failed.setRequestId(requestId);
            failed.setSuccess(false);
            failed.setCode(ApiErrorCode.LIVE_REQUEST_SEND_FAILED.getCode());
            failed.setMessage(ApiErrorCode.LIVE_REQUEST_SEND_FAILED.getDefaultMessage());
            return failed;
        }

        try {
            return future.get(ackTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            WsCommandAckResult timeoutResult = new WsCommandAckResult();
            timeoutResult.setRequestId(requestId);
            timeoutResult.setSuccess(false);
            timeoutResult.setTimedOut(true);
            timeoutResult.setCode(ApiErrorCode.LIVE_ACK_TIMEOUT.getCode());
            timeoutResult.setMessage(ApiErrorCode.LIVE_ACK_TIMEOUT.getDefaultMessage());
            return timeoutResult;
        } catch (Exception e) {
            commandResponseHandler.removePendingCommand(requestId);
            WsCommandAckResult failed = new WsCommandAckResult();
            failed.setRequestId(requestId);
            failed.setSuccess(false);
            failed.setCode(ApiErrorCode.INTERNAL_ERROR.getCode());
            failed.setMessage("等待设备确认失败");
            return failed;
        } finally {
            if (future.isDone()) {
                commandResponseHandler.removePendingCommand(requestId);
            }
        }
    }

    public void registerLiveWebSession(String deviceId, WebSocketSession session) {
        liveWebSessionService.registerSession(deviceId, session);
    }

    public void removeLiveWebSession(String deviceId) {
        liveWebSessionService.removeSession(deviceId);
    }

    public Map<String, WebSocketSession> getLiveWebSessions() {
        return null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = getDeviceIdFromSession(session);
        if (deviceId != null) {
            sessions.remove(deviceId);
            appWebSocketService.markAsDisconnected(deviceId);
            liveSessionService.markStopped(deviceId);
            commandResponseHandler.failPendingCommands(deviceId);
            log.info("设备 {} 已断开连接", deviceId);
        }
    }

    public boolean sendMessage(String deviceId, String message) {
        SessionInfo info = sessions.get(deviceId);
        if (info != null && info.session.isOpen()) {
            try {
                info.session.sendMessage(new TextMessage(message));
                info.lastActiveTime = System.currentTimeMillis();
                return true;
            } catch (IOException e) {
                log.warn("向设备 {} 发送消息失败: {}", deviceId, e.getMessage());
                sessions.remove(deviceId);
                appWebSocketService.markAsDisconnected(deviceId);
                liveSessionService.markStopped(deviceId);
                commandResponseHandler.failPendingCommands(deviceId);
                return false;
            }
        }
        return false;
    }

    private void checkSessions() {
        long currentTime = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            SessionInfo info = entry.getValue();
            if (currentTime - info.lastActiveTime > TIMEOUT) {
                try {
                    info.session.close();
                    log.info("设备 {} 连接超时，已关闭", entry.getKey());
                } catch (Exception e) {
                    log.warn("关闭超时会话失败，deviceId={}", entry.getKey());
                }
                appWebSocketService.markAsDisconnected(entry.getKey());
                liveSessionService.markStopped(entry.getKey());
                commandResponseHandler.failPendingCommands(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void updateActiveTime(WebSocketSession session) {
        String deviceId = getDeviceIdFromSession(session);
        if (deviceId != null) {
            SessionInfo info = sessions.get(deviceId);
            if (info != null) {
                info.lastActiveTime = System.currentTimeMillis();
            }
        }
    }

    private String getDeviceIdFromSession(WebSocketSession session) {
        String deviceId = (String) session.getAttributes().get("deviceId");
        if (deviceId != null && !deviceId.isBlank()) {
            return deviceId;
        }

        for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
            if (entry.getValue().session.equals(session)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String getDeviceIdFromUri(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst("deviceId");
    }

    public static class SessionInfo {
        public WebSocketSession session;
        public long lastActiveTime;

        public SessionInfo(WebSocketSession session, long lastActiveTime) {
            this.session = session;
            this.lastActiveTime = lastActiveTime;
        }
    }
}

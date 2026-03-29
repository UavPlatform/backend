package com.drone.server.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.drone.pojo.dto.UavStatusDto;
import com.drone.pojo.dto.WsEnvelope;
import com.drone.server.exception.ApiErrorCode;
import com.drone.service.AppWebSocketService;
import com.drone.service.LiveSessionService;
import com.drone.service.UavStatusService;
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

@Component
@Slf4j
public class DroneWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private UavStatusService uavStatusService;

    @Autowired
    private LiveSessionService liveSessionService;

    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> liveWebSessions = new ConcurrentHashMap<>();
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final long TIMEOUT = 60000;
    private static final long HEARTBEAT_INTERVAL = 30000;

    public DroneWebSocketHandler() {
        scheduler.scheduleAtFixedRate(this::checkSessions, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
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
        sendEnvelope(session, envelope);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        updateActiveTime(session);

        try {
            if ("ping".equalsIgnoreCase(payload.trim())) {
                session.sendMessage(new TextMessage("pong"));
                return;
            }

            JSONObject json = JSON.parseObject(payload);
            if (json == null) {
                sendWsError(session, ApiErrorCode.INVALID_MESSAGE, "消息体不能为空");
                return;
            }

            String type = json.getString("type");
            if (type == null || type.isBlank()) {
                sendWsError(session, ApiErrorCode.INVALID_MESSAGE, "消息缺少 type");
                return;
            }

            switch (type.toLowerCase()) {
                case "event" -> handleEvent(json, session);
                case "response", "error" -> handleCommandResponse(json, session);
                default -> handleLegacyMessage(type, json, session);
            }
        } catch (Exception e) {
            log.warn("处理 App WebSocket 消息失败: {}", e.getMessage());
            sendWsError(session, ApiErrorCode.INVALID_MESSAGE, "无法解析 WebSocket 消息");
        }
    }

    private void handleEvent(JSONObject json, WebSocketSession session) throws IOException {
        String eventName = json.getString("name");
        if (eventName == null || eventName.isBlank()) {
            sendWsError(session, ApiErrorCode.INVALID_MESSAGE, "event 消息缺少 name");
            return;
        }

        switch (eventName.toUpperCase()) {
            case "PING" -> handleEnvelopePing(json, session);
            case "PONG" -> {
                // 心跳已收到，activeTime 在入口已刷新
            }
            case "UAV_STATUS" -> handleUavStatus(json, session);
            case "LIVE_STARTED" -> handleLiveStarted(json, session);
            case "LIVE_STOPPED" -> handleLiveStopped(session);
            default -> sendWsError(session, ApiErrorCode.UNSUPPORTED_MESSAGE, "暂不支持的 event: " + eventName);
        }
    }

    private void handleLegacyMessage(String type, JSONObject json, WebSocketSession session) throws IOException {
        if (isLegacyAckType(type, json)) {
            handleCommandResponse(json, session);
            return;
        }

        switch (type.toUpperCase()) {
            case "PING" -> session.sendMessage(new TextMessage("pong"));
            case "PONG" -> {
                // 兼容旧协议心跳消息
            }
            case "UAV_STATUS" -> handleUavStatus(json, session);
            case "LIVE_STARTED" -> handleLiveStarted(json, session);
            case "LIVE_STOPPED" -> handleLiveStopped(session);
            default -> sendWsError(session, ApiErrorCode.UNSUPPORTED_MESSAGE, "暂不支持的消息类型: " + type);
        }
    }

    private void handleEnvelopePing(JSONObject json, WebSocketSession session) {
        WsEnvelope pong = new WsEnvelope();
        pong.setType("event");
        pong.setName("PONG");
        pong.setReplyTo(json.getString("id"));
        pong.setDeviceId(getDeviceIdFromSession(session));
        pong.setTimestamp(System.currentTimeMillis());
        pong.setSuccess(true);
        pong.setMessage("pong");
        sendEnvelope(session, pong);
    }

    private void handleUavStatus(JSONObject json, WebSocketSession session) {
        try {
            String deviceId = getDeviceIdFromSession(session);
            if (deviceId == null) {
                return;
            }

            JSONObject data = extractData(json);
            if (data == null) {
                sendWsError(session, ApiErrorCode.INVALID_MESSAGE, "UAV_STATUS 缺少 data");
                return;
            }

            UavStatusDto status = data.toJavaObject(UavStatusDto.class);
            status.setDeviceId(deviceId);
            status.setReceivedAt(System.currentTimeMillis());
            uavStatusService.updateUavStatus(deviceId, status);

            sendToLiveWebClient(deviceId, status);
            log.info("收到无人机 {} 的状态信息，操作：{}", status.getUavName(), status.getOperation());
        } catch (Exception e) {
            log.warn("处理无人机状态消息失败: {}", e.getMessage());
            sendWsError(session, ApiErrorCode.INVALID_MESSAGE, "无人机状态消息格式错误");
        }
    }

    private void handleCommandResponse(JSONObject json, WebSocketSession session) {
        String requestId = firstNonBlank(json.getString("replyTo"), json.getString("requestId"));
        if (requestId == null || requestId.isBlank()) {
            sendWsError(session, ApiErrorCode.INVALID_MESSAGE, "响应消息缺少 replyTo");
            return;
        }

        PendingCommand pendingCommand = pendingCommands.remove(requestId);
        if (pendingCommand == null) {
            log.info("收到未知命令应答，requestId={}", requestId);
            return;
        }

        WsCommandAckResult ackResult = new WsCommandAckResult();
        ackResult.setRequestId(requestId);
        ackResult.setSuccess(resolveAckSuccess(json));
        ackResult.setCode(firstNonBlank(
                json.getString("code"),
                ackResult.isSuccess() ? "OK" : ApiErrorCode.LIVE_START_REJECTED.getCode()
        ));
        ackResult.setMessage(firstNonBlank(
                json.getString("message"),
                ackResult.isSuccess() ? "设备已确认命令" : "设备拒绝执行命令"
        ));
        pendingCommand.future.complete(ackResult);

        if ("START_LIVE".equalsIgnoreCase(pendingCommand.commandName)) {
            if (ackResult.isSuccess()) {
                liveSessionService.markRunning(pendingCommand.deviceId, pendingCommand.roomId, requestId);
            } else {
                liveSessionService.markFailed(pendingCommand.deviceId);
            }
        }
    }

    private void handleLiveStarted(JSONObject json, WebSocketSession session) {
        String deviceId = getDeviceIdFromSession(session);
        if (deviceId == null) {
            return;
        }

        JSONObject data = extractData(json);
        String roomId = data != null ? data.getString("roomId") : json.getString("roomId");
        String requestId = firstNonBlank(json.getString("replyTo"), json.getString("requestId"));
        liveSessionService.markRunning(deviceId, roomId, requestId);
    }

    private void handleLiveStopped(WebSocketSession session) {
        String deviceId = getDeviceIdFromSession(session);
        if (deviceId == null) {
            return;
        }
        liveSessionService.markStopped(deviceId);
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
        PendingCommand pendingCommand = new PendingCommand(
                deviceId,
                "START_LIVE",
                roomId,
                System.currentTimeMillis() + startingTtlMillis,
                future
        );
        pendingCommands.put(requestId, pendingCommand);
        liveSessionService.markStarting(deviceId, roomId, requestId, startingTtlMillis);

        if (!sendMessage(deviceId, JSON.toJSONString(payload))) {
            pendingCommands.remove(requestId);
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
            pendingCommands.remove(requestId);
            WsCommandAckResult failed = new WsCommandAckResult();
            failed.setRequestId(requestId);
            failed.setSuccess(false);
            failed.setCode(ApiErrorCode.INTERNAL_ERROR.getCode());
            failed.setMessage("等待设备确认失败");
            return failed;
        } finally {
            if (future.isDone()) {
                pendingCommands.remove(requestId);
            }
        }
    }

    private void sendToLiveWebClient(String deviceId, UavStatusDto status) {
        WebSocketSession webSession = liveWebSessions.get(deviceId);
        if (webSession != null && webSession.isOpen()) {
            WsEnvelope envelope = new WsEnvelope();
            envelope.setType("event");
            envelope.setName("UAV_STATUS_UPDATE");
            envelope.setDeviceId(deviceId);
            envelope.setTimestamp(System.currentTimeMillis());
            envelope.setData(status);
            sendEnvelope(webSession, envelope);
        }
    }

    public void registerLiveWebSession(String deviceId, WebSocketSession session) {
        liveWebSessions.put(deviceId, session);
        log.info("Web端已注册为设备 {} 的直播客户端", deviceId);
    }

    public void removeLiveWebSession(String deviceId) {
        liveWebSessions.remove(deviceId);
        log.info("Web端已取消注册为设备 {} 的直播客户端", deviceId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = getDeviceIdFromSession(session);
        if (deviceId != null) {
            sessions.remove(deviceId);
            appWebSocketService.markAsDisconnected(deviceId);
            liveSessionService.markStopped(deviceId);
            failPendingCommands(deviceId);
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
                failPendingCommands(deviceId);
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
                failPendingCommands(entry.getKey());
                return true;
            }
            return false;
        });

        pendingCommands.entrySet().removeIf(entry -> {
            PendingCommand pendingCommand = entry.getValue();
            if (currentTime <= pendingCommand.expiresAt) {
                return false;
            }

            WsCommandAckResult timeoutResult = new WsCommandAckResult();
            timeoutResult.setRequestId(entry.getKey());
            timeoutResult.setSuccess(false);
            timeoutResult.setTimedOut(true);
            timeoutResult.setCode(ApiErrorCode.LIVE_ACK_TIMEOUT.getCode());
            timeoutResult.setMessage(ApiErrorCode.LIVE_ACK_TIMEOUT.getDefaultMessage());
            pendingCommand.future.complete(timeoutResult);
            liveSessionService.markFailed(pendingCommand.deviceId);
            return true;
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

    private JSONObject extractData(JSONObject json) {
        JSONObject data = json.getJSONObject("data");
        if (data != null) {
            return data;
        }
        return json.containsKey("uavId") ? json : null;
    }

    private boolean resolveAckSuccess(JSONObject json) {
        Boolean success = json.getBoolean("success");
        if (success != null) {
            return success;
        }
        return !"error".equalsIgnoreCase(json.getString("type"));
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private void sendWsError(WebSocketSession session, ApiErrorCode errorCode, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        WsEnvelope error = new WsEnvelope();
        error.setType("error");
        error.setName("ERROR");
        error.setDeviceId(getDeviceIdFromSession(session));
        error.setTimestamp(System.currentTimeMillis());
        error.setSuccess(false);
        error.setCode(errorCode.getCode());
        error.setMessage(message);
        sendEnvelope(session, error);
    }

    private void failPendingCommands(String deviceId) {
        pendingCommands.entrySet().removeIf(entry -> {
            PendingCommand pendingCommand = entry.getValue();
            if (!pendingCommand.deviceId.equals(deviceId)) {
                return false;
            }

            WsCommandAckResult failed = new WsCommandAckResult();
            failed.setRequestId(entry.getKey());
            failed.setSuccess(false);
            failed.setCode(ApiErrorCode.UAV_NOT_CONNECTED.getCode());
            failed.setMessage(ApiErrorCode.UAV_NOT_CONNECTED.getDefaultMessage());
            pendingCommand.future.complete(failed);
            return true;
        });
    }

    private void sendEnvelope(WebSocketSession session, WsEnvelope envelope) {
        try {
            session.sendMessage(new TextMessage(JSON.toJSONString(envelope)));
        } catch (IOException e) {
            log.warn("发送 WebSocket 消息失败: {}", e.getMessage());
        }
    }

    private boolean isLegacyAckType(String type, JSONObject json) {
        if ("START_LIVE_ACK".equalsIgnoreCase(type) || "START_LIVE_RESPONSE".equalsIgnoreCase(type)) {
            return true;
        }
        return (json.containsKey("replyTo") || json.containsKey("requestId"))
                && (json.containsKey("success") || json.containsKey("code") || json.containsKey("message"));
    }

    private static class SessionInfo {
        WebSocketSession session;
        long lastActiveTime;

        SessionInfo(WebSocketSession session, long lastActiveTime) {
            this.session = session;
            this.lastActiveTime = lastActiveTime;
        }
    }

    private static class PendingCommand {
        final String deviceId;
        final String commandName;
        final String roomId;
        final long expiresAt;
        final CompletableFuture<WsCommandAckResult> future;

        PendingCommand(String deviceId, String commandName, String roomId, long expiresAt, CompletableFuture<WsCommandAckResult> future) {
            this.deviceId = deviceId;
            this.commandName = commandName;
            this.roomId = roomId;
            this.expiresAt = expiresAt;
            this.future = future;
        }
    }
}

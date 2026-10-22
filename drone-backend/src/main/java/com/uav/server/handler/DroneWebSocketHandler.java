package com.uav.server.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.uav.live.pojo.dto.WsEnvelope;
import com.uav.live.service.AppWebSocketService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.ws.DroneSessionRegistry;
import com.uav.server.ws.WsMessageNames;
import com.uav.server.ws.handler.WsMessageRouter;
import com.uav.server.ws.service.WsMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Device WebSocket endpoint ({@code /ws/drone}).
 *
 * <p>Connection lifecycle and routing live here; session ownership and command
 * acknowledgements are delegated to {@link DroneSessionRegistry} and
 * {@code DroneCommandService}.
 */
@Component
@Slf4j
public class DroneWebSocketHandler extends TextWebSocketHandler {

    private final AppWebSocketService appWebSocketService;
    private final WsMessageRouter messageRouter;
    private final WsMessageService messageService;
    private final DroneSessionRegistry sessionRegistry;

    public DroneWebSocketHandler(AppWebSocketService appWebSocketService,
                                 WsMessageRouter messageRouter,
                                 WsMessageService messageService,
                                 DroneSessionRegistry sessionRegistry) {
        this.appWebSocketService = appWebSocketService;
        this.messageRouter = messageRouter;
        this.messageService = messageService;
        this.sessionRegistry = sessionRegistry;
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

        sessionRegistry.register(deviceId, session);
        appWebSocketService.markAsConnected(deviceId);
        log.info("设备 {} 已连接", deviceId);

        WsEnvelope envelope = new WsEnvelope();
        envelope.setType(WsMessageNames.TYPE_EVENT);
        envelope.setName(WsMessageNames.CONNECT_SUCCESS);
        envelope.setDeviceId(deviceId);
        envelope.setTimestamp(System.currentTimeMillis());
        envelope.setSuccess(true);
        envelope.setMessage("连接成功");
        messageService.send(session, envelope);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        sessionRegistry.touch(session);

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
        } catch (Exception error) {
            log.warn("处理 App WebSocket 消息失败: {}", error.getMessage());
            messageService.sendError(session, ApiErrorCode.INVALID_MESSAGE, "无法解析 WebSocket 消息");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.unregister(session);
    }

    public boolean isDeviceConnected(String deviceId) {
        return sessionRegistry.isConnected(deviceId);
    }

    public boolean sendMessage(String deviceId, String message) {
        return sessionRegistry.send(deviceId, message);
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
}

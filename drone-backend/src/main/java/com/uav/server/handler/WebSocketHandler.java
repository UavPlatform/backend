package com.uav.server.handler;

import com.uav.server.ws.service.LiveWebSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Slf4j
public class WebSocketHandler extends TextWebSocketHandler {

    private final LiveWebSessionService liveWebSessionService;

    public WebSocketHandler(LiveWebSessionService liveWebSessionService) {
        this.liveWebSessionService = liveWebSessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String deviceId = getDeviceId(session);
        if (deviceId != null && !deviceId.isBlank()) {
            liveWebSessionService.registerSession(deviceId, session);
        }
        log.info("Web端连接已建立");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = getDeviceId(session);
        if (deviceId != null && !deviceId.isBlank()) {
            liveWebSessionService.removeSession(deviceId);
        }
        log.info("Web端连接已关闭");
    }

    private String getDeviceId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst("deviceId");
    }
}

package com.drone.server.handler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import com.drone.server.ws.service.LiveWebSessionService;


@Component
public class WebWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private DroneWebSocketHandler droneWebSocketHandler;

    @Autowired
    private LiveWebSessionService liveWebSessionService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String deviceId = getDeviceId(session);
        if (deviceId != null && !deviceId.isBlank()) {
            droneWebSocketHandler.registerLiveWebSession(deviceId, session);
        } else {
            liveWebSessionService.registerDashboardSession(session);
        }
        System.out.println("Web端连接已建立");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String deviceId = getDeviceId(session);
        if (deviceId != null && !deviceId.isBlank()) {
            droneWebSocketHandler.removeLiveWebSession(deviceId);
        } else {
            liveWebSessionService.removeDashboardSession(session);
        }
        System.out.println("Web端连接已关闭");
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

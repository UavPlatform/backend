package com.drone.server.handler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class WebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private DroneWebSocketHandler droneWebSocketHandler;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 从URL参数中获取设备ID
        String query = session.getUri().getQuery();
        if (query != null && query.contains("deviceId")) {
            String deviceId = query.split("=")[1];
            // 注册为开播的Web端连接
            droneWebSocketHandler.registerLiveWebSession(deviceId, session);
        }
        log.info("Web端连接已建立");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 从URL参数中获取设备ID
        String query = session.getUri().getQuery();
        if (query != null && query.contains("deviceId")) {
            String deviceId = query.split("=")[1];
            // 移除开播的Web端连接
            droneWebSocketHandler.removeLiveWebSession(deviceId);
        }
        log.info("Web端连接已关闭");
    }
}

package com.drone.server.handler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;


@Component
public class WebWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private DroneWebSocketHandler droneWebSocketHandler;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //获取设备ID
        String query = session.getUri().getQuery();
        if (query != null && query.contains("deviceId")) {
            String deviceId = query.split("=")[1];
            droneWebSocketHandler.registerLiveWebSession(deviceId, session);
        }
        System.out.println("Web端连接已建立");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        //获取设备ID
        String query = session.getUri().getQuery();
        if (query != null && query.contains("deviceId")) {
            String deviceId = query.split("=")[1];
            droneWebSocketHandler.removeLiveWebSession(deviceId);
        }
        System.out.println("Web端连接已关闭");
    }
}
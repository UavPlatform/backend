package com.drone.server.ws.service;

import org.springframework.web.socket.WebSocketSession;

public interface LiveWebSessionProvider {
    WebSocketSession getSession(String deviceId);
    
    void registerSession(String deviceId, WebSocketSession session);
    
    void removeSession(String deviceId);
}

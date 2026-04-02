package com.drone.server.ws.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LiveWebSessionService implements LiveWebSessionProvider {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public WebSocketSession getSession(String deviceId) {
        return sessions.get(deviceId);
    }

    @Override
    public void registerSession(String deviceId, WebSocketSession session) {
        sessions.put(deviceId, session);
        log.info("Web端已注册为设备 {} 的直播客户端", deviceId);
    }

    @Override
    public void removeSession(String deviceId) {
        sessions.remove(deviceId);
        log.info("Web端已取消注册为设备 {} 的直播客户端", deviceId);
    }
}

package com.drone.server.ws.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Service
@Slf4j
public class LiveWebSessionService implements LiveWebSessionProvider {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> dashboardSessions = new CopyOnWriteArraySet<>();

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

    public void registerDashboardSession(WebSocketSession session) {
        dashboardSessions.add(session);
        log.info("Web端仪表盘会话已建立");
    }

    public void removeDashboardSession(WebSocketSession session) {
        dashboardSessions.remove(session);
        log.info("Web端仪表盘会话已关闭");
    }

    public Collection<WebSocketSession> getDashboardSessions() {
        return dashboardSessions;
    }
}

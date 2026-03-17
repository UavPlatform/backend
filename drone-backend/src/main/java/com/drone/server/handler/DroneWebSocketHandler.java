package com.drone.server.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DroneWebSocketHandler extends TextWebSocketHandler {

    //存储设备ID与WebSocket会话的映射
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    //定时任务线程池，检测心跳
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 连接超时时间（毫秒）
    private static final long TIMEOUT = 60000; // 60秒

    //心跳间隔30秒
    private static final long HEARTBEAT_INTERVAL = 30000;

    public DroneWebSocketHandler() {
        //启动心跳检测任务
        scheduler.scheduleAtFixedRate(this::checkSessions, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //从URL参数中获取设备ID
        String query = session.getUri().getQuery();
        if (query != null && query.contains("deviceId")) {
            String deviceId = query.split("=")[1];
            sessions.put(deviceId, new SessionInfo(session, System.currentTimeMillis()));
            log.info("设备 " + deviceId + " 已连接");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        //App端发送的消息
        String payload = message.getPayload();
        if ("ping".equals(payload)) {
            // 回复心跳
            session.sendMessage(new TextMessage("pong"));
            // 更新活跃时间
            updateActiveTime(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 移除断开的连接
        sessions.entrySet().removeIf(entry -> entry.getValue().session.equals(session));
        log.info("连接已关闭");
    }

    // 向指定设备发送消息
    public boolean sendMessage(String deviceId, String message) {
        SessionInfo info = sessions.get(deviceId);
        if (info != null && info.session.isOpen()) {
            try {
                info.session.sendMessage(new TextMessage(message));
                info.lastActiveTime = System.currentTimeMillis();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                // 发送失败，移除连接
                sessions.remove(deviceId);
                return false;
            }
        }
        return false;
    }

    // 心跳检测
    private void checkSessions() {
        long currentTime = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            SessionInfo info = entry.getValue();
            if (currentTime - info.lastActiveTime > TIMEOUT) {
                try {
                    info.session.close();
                    log.info("设备 " + entry.getKey() + " 连接超时，已关闭");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return true;
            }
            return false;
        });
    }

    // 更新活跃时间
    private void updateActiveTime(WebSocketSession session) {
        sessions.entrySet().forEach(entry -> {
            if (entry.getValue().session.equals(session)) {
                entry.getValue().lastActiveTime = System.currentTimeMillis();
            }
        });
    }

    // 会话信息类
    private static class SessionInfo {
        WebSocketSession session;
        long lastActiveTime;

        SessionInfo(WebSocketSession session, long lastActiveTime) {
            this.session = session;
            this.lastActiveTime = lastActiveTime;
        }
    }
}
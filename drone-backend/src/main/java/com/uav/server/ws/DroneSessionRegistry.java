package com.uav.server.ws;

import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.server.ws.handler.WsCommandResponseHandler;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns device WebSocket sessions, heartbeat eviction and cleanup on disconnect.
 *
 * <p>All writes to a session are serialized on the session object, matching
 * {@code WsMessageService}; Tomcat's basic remote does not allow concurrent writes.
 */
@Component
@Slf4j
public class DroneSessionRegistry {

    private static final String DEVICE_ID_ATTRIBUTE = "deviceId";
    private static final long TIMEOUT_MILLIS = 60_000;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 30_000;

    private final AppWebSocketService appWebSocketService;
    private final LiveSessionService liveSessionService;
    private final WsCommandResponseHandler commandResponseHandler;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public DroneSessionRegistry(AppWebSocketService appWebSocketService,
                                LiveSessionService liveSessionService,
                                WsCommandResponseHandler commandResponseHandler) {
        this.appWebSocketService = appWebSocketService;
        this.liveSessionService = liveSessionService;
        this.commandResponseHandler = commandResponseHandler;
        scheduler.scheduleAtFixedRate(this::evictIdleSessions,
                HEARTBEAT_INTERVAL_MILLIS, HEARTBEAT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void register(String deviceId, WebSocketSession session) {
        session.getAttributes().put(DEVICE_ID_ATTRIBUTE, deviceId);
        sessions.put(deviceId, new SessionInfo(session, System.currentTimeMillis()));
    }

    public void unregister(WebSocketSession session) {
        String deviceId = deviceIdFromSession(session);
        if (deviceId != null) {
            sessions.remove(deviceId);
            handleConnectionLoss(deviceId);
            log.info("设备 {} 已断开连接", deviceId);
        }
    }

    public boolean isConnected(String deviceId) {
        SessionInfo info = sessions.get(deviceId);
        return info != null && info.session.isOpen();
    }

    public void touch(WebSocketSession session) {
        String deviceId = deviceIdFromSession(session);
        if (deviceId == null) {
            return;
        }
        SessionInfo info = sessions.get(deviceId);
        if (info != null) {
            info.lastActiveTime = System.currentTimeMillis();
        }
    }

    public boolean send(String deviceId, String message) {
        SessionInfo info = sessions.get(deviceId);
        if (info == null || !info.session.isOpen()) {
            return false;
        }
        try {
            synchronized (info.session) {
                info.session.sendMessage(new TextMessage(message));
            }
            info.lastActiveTime = System.currentTimeMillis();
            return true;
        } catch (IOException error) {
            log.warn("向设备 {} 发送消息失败: {}", deviceId, error.getMessage());
            sessions.remove(deviceId);
            handleConnectionLoss(deviceId);
            return false;
        }
    }

    public void handleConnectionLoss(String deviceId) {
        appWebSocketService.markAsDisconnected(deviceId);
        liveSessionService.markStopped(deviceId);
        commandResponseHandler.failPendingCommands(deviceId);
    }

    public String deviceIdFromSession(WebSocketSession session) {
        String deviceId = (String) session.getAttributes().get(DEVICE_ID_ATTRIBUTE);
        if (deviceId != null && !deviceId.isBlank()) {
            return deviceId;
        }
        for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
            if (entry.getValue().session.equals(session)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void evictIdleSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            SessionInfo info = entry.getValue();
            if (now - info.lastActiveTime <= TIMEOUT_MILLIS) {
                return false;
            }
            try {
                info.session.close();
                log.info("设备 {} 连接超时，已关闭", entry.getKey());
            } catch (Exception error) {
                log.warn("关闭超时会话失败，deviceId={}", entry.getKey());
            }
            handleConnectionLoss(entry.getKey());
            return true;
        });
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public static final class SessionInfo {
        public final WebSocketSession session;
        public long lastActiveTime;

        public SessionInfo(WebSocketSession session, long lastActiveTime) {
            this.session = session;
            this.lastActiveTime = lastActiveTime;
        }
    }
}

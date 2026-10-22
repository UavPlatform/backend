package com.uav.server.ws;

import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.ws.handler.WsCommandAckResult;
import com.uav.server.ws.handler.WsCommandResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for device session ownership, idle eviction and disconnect cleanup. */
class DroneSessionRegistryTest {

    private static final String DEVICE = "dev-1";

    private final AppWebSocketService appWebSocketService = mock(AppWebSocketService.class);
    private final LiveSessionService liveSessionService = mock(LiveSessionService.class);
    private final WsCommandResponseHandler responseHandler = new WsCommandResponseHandler();
    private DroneSessionRegistry registry;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        registry = new DroneSessionRegistry(appWebSocketService, liveSessionService, responseHandler);
        session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
    }

    @AfterEach
    void stopScheduler() {
        registry.shutdown();
    }

    @Test
    void registeredOpenSessionIsConnectedAndReceivesMessages() throws Exception {
        registry.register(DEVICE, session);

        assertThat(registry.isConnected(DEVICE)).isTrue();
        assertThat(registry.deviceIdFromSession(session)).isEqualTo(DEVICE);
        assertThat(registry.send(DEVICE, "hello")).isTrue();
        verify(session).sendMessage(new TextMessage("hello"));
    }

    @Test
    void sendFailureMarksDeviceDisconnectedAndFailsPendingCommands() throws Exception {
        registry.register(DEVICE, session);
        CompletableFuture<WsCommandAckResult> pending = pendingCommand("req-1", DEVICE);
        CompletableFuture<WsCommandAckResult> otherDevice = pendingCommand("req-2", "dev-2");
        doThrow(new IOException("broken pipe")).when(session).sendMessage(any());

        assertThat(registry.send(DEVICE, "payload")).isFalse();

        assertThat(registry.isConnected(DEVICE)).isFalse();
        verify(appWebSocketService).markAsDisconnected(DEVICE);
        verify(liveSessionService).markStopped(DEVICE);
        assertThat(pending).isCompleted();
        assertThat(pending.join().isSuccess()).isFalse();
        assertThat(pending.join().getCode()).isEqualTo(ApiErrorCode.UAV_NOT_CONNECTED.getCode());
        assertThat(otherDevice).isNotDone();
    }

    @Test
    void idleSessionOlderThanSixtySecondsIsEvictedAndCleanedUp() throws Exception {
        registry.register(DEVICE, session);
        CompletableFuture<WsCommandAckResult> pending = pendingCommand("req-1", DEVICE);
        sessionInfo().lastActiveTime = System.currentTimeMillis() - 61_000;

        ReflectionTestUtils.invokeMethod(registry, "evictIdleSessions");

        verify(session).close();
        assertThat(registry.isConnected(DEVICE)).isFalse();
        verify(appWebSocketService).markAsDisconnected(DEVICE);
        verify(liveSessionService).markStopped(DEVICE);
        assertThat(pending.join().getCode()).isEqualTo(ApiErrorCode.UAV_NOT_CONNECTED.getCode());
    }

    @Test
    void recentlyActiveSessionIsNotEvicted() throws Exception {
        registry.register(DEVICE, session);
        sessionInfo().lastActiveTime = System.currentTimeMillis() - 59_000;
        registry.touch(session);

        ReflectionTestUtils.invokeMethod(registry, "evictIdleSessions");

        verify(session, never()).close();
        assertThat(registry.isConnected(DEVICE)).isTrue();
        verify(appWebSocketService, never()).markAsDisconnected(DEVICE);
    }

    @Test
    void unregisterRemovesSessionAndRunsDisconnectCleanup() {
        registry.register(DEVICE, session);
        CompletableFuture<WsCommandAckResult> pending = pendingCommand("req-1", DEVICE);

        registry.unregister(session);

        assertThat(registry.isConnected(DEVICE)).isFalse();
        verify(appWebSocketService).markAsDisconnected(DEVICE);
        verify(liveSessionService).markStopped(DEVICE);
        assertThat(pending).isCompleted();
    }

    private CompletableFuture<WsCommandAckResult> pendingCommand(String requestId, String deviceId) {
        CompletableFuture<WsCommandAckResult> future = new CompletableFuture<>();
        responseHandler.addPendingCommand(requestId, new WsCommandResponseHandler.PendingCommand(
                deviceId, "STOP_LIVE", null, System.currentTimeMillis() + 10_000, future));
        return future;
    }

    @SuppressWarnings("unchecked")
    private DroneSessionRegistry.SessionInfo sessionInfo() {
        Map<String, DroneSessionRegistry.SessionInfo> sessions =
                (Map<String, DroneSessionRegistry.SessionInfo>) ReflectionTestUtils.getField(registry, "sessions");
        return sessions.get(DEVICE);
    }
}

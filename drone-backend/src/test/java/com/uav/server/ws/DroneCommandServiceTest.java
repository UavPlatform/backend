package com.uav.server.ws;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.uav.live.service.LiveSessionService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.ws.handler.WsCommandAckResult;
import com.uav.server.ws.handler.WsCommandResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for command dispatch and acknowledgement handling moved out of DroneWebSocketHandler. */
class DroneCommandServiceTest {

    private static final String DEVICE = "dev-1";

    private final DroneSessionRegistry registry = mock(DroneSessionRegistry.class);
    private final LiveSessionService liveSessionService = mock(LiveSessionService.class);
    private WsCommandResponseHandler responseHandler;
    private DroneCommandService service;

    @BeforeEach
    void setUp() {
        responseHandler = new WsCommandResponseHandler();
        service = new DroneCommandService(registry, responseHandler, liveSessionService);
    }

    @Test
    void sendCommandWithAckReturnsDeviceAcknowledgement() {
        when(registry.isConnected(DEVICE)).thenReturn(true);
        AtomicReference<String> sent = new AtomicReference<>();
        when(registry.send(eq(DEVICE), anyString())).thenAnswer(invocation -> {
            String payload = invocation.getArgument(1);
            sent.set(payload);
            String requestId = JSON.parseObject(payload).getString("id");
            CompletableFuture.runAsync(() -> responseHandler.handle(
                    JSON.parseObject("{\"type\":\"response\",\"replyTo\":\"" + requestId + "\",\"success\":true}"),
                    null));
            return true;
        });

        WsCommandAckResult result = service.sendCommandWithAck(DEVICE, "CUSTOM", Map.of("k", "v"), 2_000);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isTimedOut()).isFalse();
        assertThat(result.getCode()).isEqualTo("OK");
        JSONObject envelope = JSON.parseObject(sent.get());
        assertThat(envelope.getString("type")).isEqualTo(WsMessageNames.TYPE_COMMAND);
        assertThat(envelope.getString("name")).isEqualTo("CUSTOM");
        assertThat(envelope.getString("deviceId")).isEqualTo(DEVICE);
        assertThat(result.getRequestId()).isEqualTo(envelope.getString("id"));
        assertThat(responseHandler.removePendingCommand(result.getRequestId())).isNull();
    }

    @Test
    void sendCommandWithAckReturnsOfflineWithoutSendingWhenDeviceDisconnected() {
        when(registry.isConnected(DEVICE)).thenReturn(false);

        WsCommandAckResult result = service.sendCommandWithAck(DEVICE, "CUSTOM", Map.of(), 1_000);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ApiErrorCode.UAV_NOT_CONNECTED.getCode());
        verify(registry, never()).send(anyString(), anyString());
    }

    @Test
    void sendFailureRemovesPendingCommandAndReportsDisconnected() {
        when(registry.isConnected(DEVICE)).thenReturn(true);
        when(registry.send(eq(DEVICE), anyString())).thenReturn(false);

        WsCommandAckResult result = service.sendCommandWithAck(DEVICE, "CUSTOM", Map.of(), 1_000);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isTimedOut()).isFalse();
        assertThat(result.getCode()).isEqualTo(ApiErrorCode.UAV_NOT_CONNECTED.getCode());
        assertThat(responseHandler.removePendingCommand(result.getRequestId())).isNull();
    }

    @Test
    void ackTimeoutReturnsTimedOutAndKeepsPendingCommandForLateAck() {
        when(registry.isConnected(DEVICE)).thenReturn(true);
        when(registry.send(eq(DEVICE), anyString())).thenReturn(true);

        WsCommandAckResult result = service.sendStopLiveCommand(DEVICE, 50);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isTimedOut()).isTrue();
        assertThat(result.getCode()).isEqualTo(ApiErrorCode.LIVE_ACK_TIMEOUT.getCode());
        // A late acknowledgement must still find the pending command.
        assertThat(responseHandler.removePendingCommand(result.getRequestId())).isNotNull();
    }

    @Test
    void startLiveSendFailureMarksLiveSessionFailed() {
        when(registry.send(eq(DEVICE), anyString())).thenReturn(false);

        WsCommandAckResult result = service.sendStartLiveCommand(DEVICE, "room", "u", "sig", 1_000, 5_000);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ApiErrorCode.LIVE_REQUEST_SEND_FAILED.getCode());
        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        verify(liveSessionService).markStarting(eq(DEVICE), eq("room"), requestId.capture(), eq(5_000L));
        verify(liveSessionService).markFailed(DEVICE);
        assertThat(responseHandler.removePendingCommand(requestId.getValue())).isNull();
    }
}

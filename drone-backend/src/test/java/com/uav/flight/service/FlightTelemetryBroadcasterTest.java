package com.uav.flight.service;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.flight.pojo.vo.FlightSampleVO;
import com.uav.live.pojo.dto.WsEnvelope;
import com.uav.server.ws.WsMessageNames;
import com.uav.server.ws.auth.WsAuthHandshakeInterceptor;
import com.uav.server.ws.service.WsMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.WebSocketSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** REQ-SUP-MON-005/007、REQ-SHIP-EXEC-004：/ws/web 实时遥测推送的订阅权限与提交后推送。 */
class FlightTelemetryBroadcasterTest {
    private final DispatchOrderRepository orders = mock(DispatchOrderRepository.class);
    private final WsMessageService messages = mock(WsMessageService.class);
    private final FlightTelemetryBroadcaster broadcaster = new FlightTelemetryBroadcaster(orders, messages);

    private static WebSocketSession session(long user, int role) {
        var s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>(Map.of(WsAuthHandshakeInterceptor.ATTR_USER_ID, user, WsAuthHandshakeInterceptor.ATTR_ROLE, role));
        when(s.getAttributes()).thenReturn(attrs); when(s.isOpen()).thenReturn(true);
        return s;
    }
    private static FlightConnection connection() {
        var c = new FlightConnection(); c.setId("c1"); c.setOrderId("o1"); c.setSerialNumber("SN1"); c.setModelCode("FC30"); c.setModelName("DJI FlyCart 30");
        return c;
    }
    private static FlightSampleVO sample() {
        return new FlightSampleVO(5L, "c1", 3, 1_000L, LocalDateTime.now(), 30.0, 120.0, 50.0, null, 80, true, 12.5);
    }

    @Test void supervisorsSeeAllShippersOnlyTheirOrderOthersRejected() {
        var order = new DispatchOrder(); order.setId("o1"); order.setUserId(10L);
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        when(orders.findById("o2")).thenReturn(Optional.empty());
        var supervisor = session(1, 2); var owner = session(10, 0); var stranger = session(11, 0); var plain = session(12, 0);
        assertThat(broadcaster.register(supervisor, null)).isTrue();
        assertThat(broadcaster.register(owner, "o1")).isTrue();
        assertThat(broadcaster.register(stranger, "o1")).isFalse();
        assertThat(broadcaster.register(plain, "")).isTrue();
        assertThat(broadcaster.register(session(13, 0), "o2")).isFalse();
        broadcaster.publish(connection(), sample());
        verify(messages).send(eq(supervisor), argThat(e -> WsMessageNames.FLIGHT_TELEMETRY.equals(e.getName())));
        verify(messages).send(eq(owner), any());
        verify(messages, never()).send(eq(stranger), any());
        verify(messages, never()).send(eq(plain), any());
        broadcaster.unregister(owner);
        when(supervisor.isOpen()).thenReturn(false);
        broadcaster.publish(connection(), sample());
        verify(messages, times(1)).send(eq(owner), any());
        verify(messages, times(1)).send(eq(supervisor), any());
    }

    @Test void envelopeCarriesNullableTelemetryAndPayload() {
        WsEnvelope e = FlightTelemetryBroadcaster.envelope(connection(), sample());
        assertThat(e.getType()).isEqualTo("event"); assertThat(e.getDeviceId()).isEqualTo("SN1"); assertThat(e.getTimestamp()).isEqualTo(1_000L);
        @SuppressWarnings("unchecked") var data = (Map<String, Object>) e.getData();
        assertThat(data).containsEntry("orderId", "o1").containsEntry("payloadKg", 12.5).containsEntry("speed", null);
    }

    @Test void publishWaitsForCommitWhenTransactionIsActive() {
        var supervisor = session(1, 2); broadcaster.register(supervisor, null);
        TransactionSynchronizationManager.initSynchronization();
        try {
            broadcaster.publish(connection(), sample());
            verifyNoInteractions(messages);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(messages).send(eq(supervisor), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}

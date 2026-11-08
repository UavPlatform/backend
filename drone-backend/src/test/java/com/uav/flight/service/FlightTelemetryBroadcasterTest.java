package com.uav.flight.service;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.pojo.vo.FlightEventVO;
import com.uav.live.pojo.dto.WsEnvelope;
import com.uav.server.ws.WsMessageNames;
import com.uav.server.ws.auth.WsAuthHandshakeInterceptor;
import com.uav.server.ws.service.WsMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.WebSocketSession;
import java.util.HashMap;
import java.util.List;
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
    private static FlightEventVO event() {
        return new FlightEventVO(7L, "e1", "SN1", "FLIGHT_CONTROLLER", "FC30", "POSITION", 1_000L, 1_500L,
                Map.of("latitudeDegE7", 300_000_000L, "longitudeDegE7", 1_200_000_000L));
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
        broadcaster.publish("o1", List.of(event()));
        verify(messages).send(eq(supervisor), argThat(e -> WsMessageNames.FLIGHT_TELEMETRY.equals(e.getName())));
        verify(messages).send(eq(owner), any());
        verify(messages, never()).send(eq(stranger), any());
        verify(messages, never()).send(eq(plain), any());
        broadcaster.unregister(owner);
        when(supervisor.isOpen()).thenReturn(false);
        broadcaster.publish("o1", List.of(event()));
        verify(messages, times(1)).send(eq(owner), any());
        verify(messages, times(1)).send(eq(supervisor), any());
    }

    @Test void envelopeCarriesTheEventWithItsOwnCaptureTime() {
        WsEnvelope e = FlightTelemetryBroadcaster.envelope("o1", event());
        assertThat(e.getType()).isEqualTo("event"); assertThat(e.getDeviceId()).isEqualTo("SN1"); assertThat(e.getTimestamp()).isEqualTo(1_000L);
        @SuppressWarnings("unchecked") var data = (Map<String, Object>) e.getData();
        assertThat(data).containsEntry("orderId", "o1").containsEntry("eventId", "e1")
                .containsEntry("type", "POSITION").containsEntry("capturedAt", 1_000L)
                .containsEntry("payload", Map.of("latitudeDegE7", 300_000_000L, "longitudeDegE7", 1_200_000_000L));
    }

    @Test void emptyPagesAreNotPushed() {
        var supervisor = session(1, 2); broadcaster.register(supervisor, null);
        broadcaster.publish("o1", List.of());
        verifyNoInteractions(messages);
    }

    @Test void publishWaitsForCommitWhenTransactionIsActive() {
        var supervisor = session(1, 2); broadcaster.register(supervisor, null);
        TransactionSynchronizationManager.initSynchronization();
        try {
            broadcaster.publish("o1", List.of(event()));
            verifyNoInteractions(messages);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(messages).send(eq(supervisor), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}

package com.uav.flight.service;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.mapper.DispatchLiveRepository;
import com.uav.flight.pojo.entity.DispatchLiveSession;
import com.uav.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DispatchLiveServiceTest {
    private final DispatchOrderRepository orders = mock(DispatchOrderRepository.class);
    private final DispatchLiveRepository sessions = mock(DispatchLiveRepository.class);
    private final DispatchLiveSigner signer = new DispatchLiveSigner(101, "dummy-test-key", true);
    private final DispatchLiveService service = new DispatchLiveService(orders, sessions, signer);
    private DispatchOrder order;

    @BeforeEach void setUp() {
        order = new DispatchOrder(); order.setId("o1"); order.setUserId(10L); order.setAssignedPilotId(7L);
        when(orders.lockOrder("o1")).thenReturn(Optional.of(order)); when(orders.findById("o1")).thenReturn(Optional.of(order));
        when(sessions.findByPilotIdAndRequestKey(any(), any())).thenReturn(Optional.empty());
        when(sessions.findTopByOrderIdOrderByOpenedAtDesc("o1")).thenReturn(Optional.empty());
    }

    @Test void pilotStartsOrderScopedSession() {
        var ticket = service.start(7L, "o1", "rk");
        assertThat(ticket.liveId()).isNotBlank();
        verify(sessions).saveAndFlush(argThat(s -> "o1".equals(s.getOrderId()) && s.getPilotId() == 7L));
    }

    @Test void retryReusesCurrentSession() {
        var s = session("RUNNING", future());
        when(sessions.findByPilotIdAndRequestKey(7L, "rk")).thenReturn(Optional.of(s));
        when(sessions.findTopByOrderIdOrderByOpenedAtDesc("o1")).thenReturn(Optional.of(s));
        assertThat(service.start(7L, "o1", "rk").liveId()).isEqualTo(s.getId());
    }

    @Test void staleSessionCannotHeartbeatReplacement() {
        var old = session("RUNNING", future()); var current = session("STARTING", future()); current.setId("other");
        when(sessions.findById(old.getId())).thenReturn(Optional.of(old));
        when(sessions.findTopByOrderIdOrderByOpenedAtDesc("o1")).thenReturn(Optional.of(current));
        var error = catchThrowable(() -> service.heartbeat(7L, "o1", old.getId(), "RUNNING"));
        assertThat(error).isInstanceOf(BusinessException.class);
    }

    private DispatchLiveSession session(String state, long lease) {
        var s = new DispatchLiveSession(); s.setId("12345678-1234-1234-1234-123456789abc"); s.setOrderId("o1");
        s.setPilotId(7L); s.setRequestKey("rk"); s.setState(state); s.setOpenedAt(LocalDateTime.now()); s.setLeaseExpiresAt(lease);
        when(sessions.findById(s.getId())).thenReturn(Optional.of(s)); return s;
    }
    private static long future() { return System.currentTimeMillis() + 60_000; }
}

package com.uav.flight.service;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.mapper.DispatchLiveRepository;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.flight.pojo.entity.DispatchLiveSession;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** REQ-PILOT-EXEC-004 / REQ-SHIP-EXEC-004：飞手推流租约与观看凭证的状态机和权限。 */
class DispatchLiveServiceTest {
    private final DispatchOrderRepository orders = mock(DispatchOrderRepository.class);
    private final FlightConnectionRepository connections = mock(FlightConnectionRepository.class);
    private final DispatchLiveRepository sessions = mock(DispatchLiveRepository.class);
    private final DispatchLiveSigner signer = new DispatchLiveSigner(101, "dummy-test-key", true);
    private final DispatchLiveService service = new DispatchLiveService(orders, connections, sessions, signer);
    private DispatchOrder order;
    private FlightConnection connection;

    @BeforeEach void setUp() {
        order = new DispatchOrder(); order.setId("o1"); order.setUserId(10L); order.setAssignedPilotId(7L);
        order.setAssignmentVersion(2); order.setStage("IN_TRANSIT");
        when(orders.lockOrder("o1")).thenReturn(Optional.of(order));
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        connection = new FlightConnection(); connection.setId("c1"); connection.setOrderId("o1"); connection.setPilotId(7L);
        connection.setAssignmentVersion(2);
        when(connections.findById("c1")).thenReturn(Optional.of(connection));
        when(connections.findByActiveOrderId("o1")).thenReturn(Optional.of(connection));
        when(sessions.findByPilotIdAndRequestKey(any(), any())).thenReturn(Optional.empty());
        when(sessions.findByActiveConnectionId(any())).thenReturn(Optional.empty());
    }

    private DispatchLiveSession session(String state, long lease) {
        var s = new DispatchLiveSession(); s.setId("12345678-1234-1234-1234-123456789abc"); s.setOrderId("o1");
        s.setConnectionId("c1"); s.setPilotId(7L); s.setRequestKey("rk"); s.setState(state);
        s.setOpenedAt(LocalDateTime.now()); s.setLeaseExpiresAt(lease); s.setActiveConnectionId("c1");
        when(sessions.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }
    private static long future() { return System.currentTimeMillis() + 60_000; }
    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getHttpStatus()).isEqualTo(status);
    }

    @Test void pilotStartsSessionAndGetsPublisherTicket() {
        var ticket = service.start(7L, "o1", "c1", "rk");
        assertThat(ticket.connectionId()).isEqualTo("c1");
        verify(sessions).saveAndFlush(argThat(s -> "STARTING".equals(s.getState()) && "c1".equals(s.getActiveConnectionId())));
    }

    @Test void startRetryReusesSessionAndMismatchedOrExpiredRetryConflicts() {
        var s = session("RUNNING", future());
        when(sessions.findByPilotIdAndRequestKey(7L, "rk")).thenReturn(Optional.of(s));
        assertThat(service.start(7L, "o1", "c1", "rk").liveId()).isEqualTo(s.getId());
        verify(sessions, never()).saveAndFlush(any());
        s.setLeaseExpiresAt(0);
        assertStatus(catchThrowable(() -> service.start(7L, "o1", "c1", "rk")), HttpStatus.CONFLICT);
    }

    @Test void startConflictsWithActiveSessionButReplacesExpiredOne() {
        var s = session("RUNNING", future());
        when(sessions.findByActiveConnectionId("c1")).thenReturn(Optional.of(s));
        assertStatus(catchThrowable(() -> service.start(7L, "o1", "c1", "rk2")), HttpStatus.CONFLICT);
        s.setLeaseExpiresAt(System.currentTimeMillis() - 1);
        service.start(7L, "o1", "c1", "rk2");
        assertThat(s.getState()).isEqualTo("STOPPED"); assertThat(s.getActiveConnectionId()).isNull();
        verify(sessions).flush();
    }

    @Test void startRejectsWrongPilotEndedConnectionWrongStageAndRaces() {
        assertStatus(catchThrowable(() -> service.start(8L, "o1", "c1", "rk")), HttpStatus.CONFLICT);
        assertStatus(catchThrowable(() -> service.start(7L, "o1", "nope", "rk")), HttpStatus.NOT_FOUND);
        connection.setAssignmentVersion(1);
        assertStatus(catchThrowable(() -> service.start(7L, "o1", "c1", "rk")), HttpStatus.CONFLICT);
        connection.setAssignmentVersion(2); connection.setEndedAt(LocalDateTime.now());
        assertStatus(catchThrowable(() -> service.start(7L, "o1", "c1", "rk")), HttpStatus.CONFLICT);
        connection.setEndedAt(null); order.setStage("DELIVERED");
        assertStatus(catchThrowable(() -> service.start(7L, "o1", "c1", "rk")), HttpStatus.CONFLICT);
        order.setStage("PILOT_ARRIVED");
        when(sessions.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertStatus(catchThrowable(() -> service.start(7L, "o1", "c1", "rk")), HttpStatus.CONFLICT);
    }

    @Test void startWithoutConfiguredTrtcIsUnavailable() {
        var off = new DispatchLiveService(orders, connections, sessions, new DispatchLiveSigner(0, "", false));
        assertStatus(catchThrowable(() -> off.start(7L, "o1", "c1", "rk")), HttpStatus.SERVICE_UNAVAILABLE);
        assertStatus(catchThrowable(() -> off.heartbeat(7L, "o1", "c1", "x", "RUNNING")), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void heartbeatExtendsLeaseOnlyForOwnerOfActiveSession() {
        var s = session("STARTING", System.currentTimeMillis() + 1000);
        var vo = service.heartbeat(7L, "o1", "c1", s.getId(), "RUNNING");
        assertThat(vo.state()).isEqualTo("RUNNING"); assertThat(vo.leaseExpiresAt()).isGreaterThan(System.currentTimeMillis() + 10_000);
        s.setPilotId(8L);
        assertStatus(catchThrowable(() -> service.heartbeat(7L, "o1", "c1", s.getId(), "RUNNING")), HttpStatus.CONFLICT);
        s.setPilotId(7L); s.setEndedAt(LocalDateTime.now());
        assertStatus(catchThrowable(() -> service.heartbeat(7L, "o1", "c1", s.getId(), "RUNNING")), HttpStatus.CONFLICT);
        assertStatus(catchThrowable(() -> service.heartbeat(7L, "o1", "c1", "missing", "RUNNING")), HttpStatus.NOT_FOUND);
    }

    @Test void onlyPilotCanStopAndViewersCannot() {
        var s = session("RUNNING", future());
        assertStatus(catchThrowable(() -> service.stop(10L, "o1", "c1", s.getId())), HttpStatus.FORBIDDEN);
        service.stop(7L, "o1", "c1", s.getId());
        assertThat(s.getState()).isEqualTo("STOPPED"); assertThat(s.getLeaseExpiresAt()).isZero();
        var ended = s.getEndedAt();
        service.stop(7L, "o1", "c1", s.getId()); // 重复停止不改写结束时间
        assertThat(s.getEndedAt()).isSameAs(ended);
    }

    @Test void currentReportsActiveSessionOnlyInTransitAndHidesFromStrangers() {
        var s = session("RUNNING", future());
        when(sessions.findByActiveConnectionId("c1")).thenReturn(Optional.of(s));
        var vo = service.current(10L, 1, "o1");
        assertThat(vo.configured()).isTrue(); assertThat(vo.state()).isEqualTo("RUNNING"); assertThat(vo.liveId()).isEqualTo(s.getId());
        s.setLeaseExpiresAt(1);
        assertThat(service.current(null, 2, "o1").state()).isEqualTo("UNAVAILABLE");
        order.setStage("PILOT_ARRIVED");
        assertThat(service.current(7L, 1, "o1").liveId()).isNull();
        when(connections.findByActiveOrderId("o1")).thenReturn(Optional.empty());
        assertThat(service.current(7L, 1, "o1").state()).isEqualTo("UNAVAILABLE");
        assertStatus(catchThrowable(() -> service.current(99L, 1, "o1")), HttpStatus.NOT_FOUND);
    }

    @Test void watchIssuesViewerTicketOnlyForRunningSessionInTransit() {
        var s = session("RUNNING", future());
        var ticket = service.watch(10L, 1, "o1", s.getId());
        assertThat(ticket.userId()).startsWith("v_").isNotEqualTo(ticket.publisherUserId());
        assertStatus(catchThrowable(() -> service.watch(99L, 1, "o1", s.getId())), HttpStatus.NOT_FOUND);
        s.setState("STARTING");
        assertStatus(catchThrowable(() -> service.watch(10L, 1, "o1", s.getId())), HttpStatus.CONFLICT);
        s.setState("RUNNING"); order.setStage("DELIVERED");
        assertStatus(catchThrowable(() -> service.watch(10L, 1, "o1", s.getId())), HttpStatus.CONFLICT);
        order.setStage("IN_TRANSIT");
        when(connections.findByActiveOrderId("o1")).thenReturn(Optional.empty());
        assertStatus(catchThrowable(() -> service.watch(null, 2, "o1", s.getId())), HttpStatus.CONFLICT);
    }

    @Test void closeConnectionEndsActiveSession() {
        var s = session("RUNNING", future());
        when(sessions.findByActiveConnectionId("c1")).thenReturn(Optional.of(s));
        service.closeConnection("c1");
        assertThat(s.getEndedAt()).isNotNull();
        service.closeConnection("other");
    }
}

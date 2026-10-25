package com.uav.flight.service;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.flight.mapper.FlightSampleRepository;
import com.uav.flight.pojo.dto.FlightConnectDTO;
import com.uav.flight.pojo.dto.FlightSampleDTO;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.flight.pojo.entity.FlightSample;
import com.uav.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** REQ-PILOT-EXEC-007 / ADR-0009：按订单的连机记录与实际采样（权限、幂等、校验、超重提示、陈旧判定）。 */
class FlightRecordServiceTest {
    private final DispatchOrderRepository orders = mock(DispatchOrderRepository.class);
    private final FlightConnectionRepository connections = mock(FlightConnectionRepository.class);
    private final FlightSampleRepository samples = mock(FlightSampleRepository.class);
    private final AircraftModelRepository models = mock(AircraftModelRepository.class);
    private final DispatchLiveService live = mock(DispatchLiveService.class);
    private final FlightRecordService service = new FlightRecordService(orders, connections, samples, models, live, mock(FlightTelemetryBroadcaster.class));
    private DispatchOrder order;

    @BeforeEach void setUp() {
        order = new DispatchOrder(); order.setId("o1"); order.setUserId(10L); order.setAssignedPilotId(7L);
        order.setAssignmentVersion(2); order.setStage("PILOT_ARRIVED"); order.setFieldsJson("{\"weightMax\":5}");
        when(orders.lockOrder("o1")).thenReturn(Optional.of(order));
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        when(connections.findByPilotIdAndRequestKey(any(), any())).thenReturn(Optional.empty());
        when(connections.findByActiveOrderId(any())).thenReturn(Optional.empty());
    }

    private static FlightConnectDTO connect(String serial) { return new FlightConnectDTO(2, "rk", serial, "M30", "Matrice 30"); }
    private static FlightSampleDTO sample(long seq, Double lat, Double lng) {
        return new FlightSampleDTO(seq, System.currentTimeMillis(), lat, lng, 100.0, 5.0, 80, true);
    }
    private static AircraftModel model(long id, String code, String name, String payload) {
        var m = new AircraftModel(); m.setId(id); m.setModelCode(code); m.setDisplayName(name); m.setMaxPayloadKg(new BigDecimal(payload)); return m;
    }
    private FlightConnection existing(String id, LocalDateTime ended) {
        var c = new FlightConnection(); c.setId(id); c.setOrderId("o1"); c.setPilotId(7L); c.setAssignmentVersion(2);
        c.setRequestKey("rk"); c.setSerialNumber("SN1"); c.setModelCode("M30"); c.setModelName("Matrice 30");
        c.setOpenedAt(LocalDateTime.now()); c.setEndedAt(ended);
        when(connections.findById(id)).thenReturn(Optional.of(c));
        return c;
    }
    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getHttpStatus()).isEqualTo(status);
    }

    @Test void connectRecordsModelSerialAndFlagsOverweightAgainstUniqueModel() {
        when(models.findAll()).thenReturn(List.of(model(1, "M30", "Matrice 30", "2.5"), model(2, "M3", "Mavic 3", "1")));
        when(models.findById(1L)).thenReturn(Optional.of(model(1, "M30", "Matrice 30", "2.5")));
        var vo = service.connect(7L, "o1", connect(" sn1 "));
        assertThat(vo.aircraftModelId()).isEqualTo(1L);
        assertThat(vo.maxPayloadKg()).isEqualByComparingTo("2.5");
        assertThat(vo.overweight()).isTrue();
        verify(connections).saveAndFlush(argThat(c -> "SN1".equals(c.getActiveSerial()) && "o1".equals(c.getActiveOrderId())));
    }

    @Test void connectReplacesPreviousActiveConnectionAndClosesItsLive() {
        var previous = existing("c0", null); previous.setActiveOrderId("o1");
        when(connections.findByActiveOrderId("o1")).thenReturn(Optional.of(previous));
        when(models.findAll()).thenReturn(List.of());
        order.setFieldsJson("{\"weightMin\":1}");
        var vo = service.connect(7L, "o1", connect("sn2"));
        assertThat(vo.aircraftModelId()).isNull(); assertThat(vo.overweight()).isFalse();
        assertThat(previous.getEndReason()).isEqualTo("REPLACED"); assertThat(previous.getActiveOrderId()).isNull();
        verify(live).closeConnection("c0");
    }

    @Test void ambiguousModelMatchIsNotBound() {
        when(models.findAll()).thenReturn(List.of(model(1, "M30", "x", "1"), model(2, "y", "Matrice 30", "1")));
        assertThat(service.connect(7L, "o1", connect("sn1")).aircraftModelId()).isNull();
    }

    @Test void connectRejectsUnknownIdentityWrongPilotWrongStageAndDuplicateSerial() {
        assertStatus(catchThrowable(() -> service.connect(7L, "o1", new FlightConnectDTO(2, "rk", "unknown", "M30", "x"))), HttpStatus.BAD_REQUEST);
        assertStatus(catchThrowable(() -> service.connect(7L, "o1", new FlightConnectDTO(2, "rk", "sn", "M30", "未知设备"))), HttpStatus.BAD_REQUEST);
        assertStatus(catchThrowable(() -> service.connect(8L, "o1", connect("sn1"))), HttpStatus.CONFLICT);
        assertStatus(catchThrowable(() -> service.connect(7L, "o1", new FlightConnectDTO(1, "rk", "sn", "M", "N"))), HttpStatus.CONFLICT);
        assertStatus(catchThrowable(() -> service.connect(7L, "missing", connect("sn1"))), HttpStatus.NOT_FOUND);
        order.setStage("DELIVERED");
        assertStatus(catchThrowable(() -> service.connect(7L, "o1", connect("sn1"))), HttpStatus.CONFLICT);
        order.setStage("IN_TRANSIT");
        when(models.findAll()).thenReturn(List.of());
        when(connections.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("serial in use"));
        assertStatus(catchThrowable(() -> service.connect(7L, "o1", connect("sn1"))), HttpStatus.CONFLICT);
    }

    @Test void connectRetryIsIdempotentButMismatchedRetryConflicts() {
        var c = existing("c1", null);
        when(connections.findByPilotIdAndRequestKey(7L, "rk")).thenReturn(Optional.of(c));
        assertThat(service.connect(7L, "o1", connect("sn1")).connectionId()).isEqualTo("c1");
        // 已完成阶段的重放仍返回原记录，不新建
        order.setStage("DELIVERED");
        assertThat(service.connect(7L, "o1", connect("sn1")).connectionId()).isEqualTo("c1");
        verify(connections, never()).saveAndFlush(any());
        assertStatus(catchThrowable(() -> service.connect(7L, "o1", connect("other"))), HttpStatus.CONFLICT);
        assertStatus(catchThrowable(() -> service.connect(7L, "o1", new FlightConnectDTO(2, "rk", "sn1", "M3", "Matrice 30"))), HttpStatus.CONFLICT);
        assertStatus(catchThrowable(() -> service.connect(7L, "o1", new FlightConnectDTO(2, "rk", "sn1", "M30", "Mavic"))), HttpStatus.CONFLICT);
    }

    @Test void sampleStoresAndAdvancesLatestOnlyForHigherSequence() {
        var c = existing("c1", null);
        when(samples.saveAndFlush(any())).thenAnswer(inv -> { FlightSample s = inv.getArgument(0); s.setId(s.getSequenceNumber() + 100); return s; });
        when(samples.findByConnectionIdAndSequenceNumber(eq("c1"), anyLong())).thenReturn(Optional.empty());
        var first = service.sample(7L, "o1", "c1", sample(5, 30.0, 120.0));
        assertThat(first.sampleId()).isEqualTo(105L); assertThat(c.getLatestSampleId()).isEqualTo(105L);
        var stored = new FlightSample(); stored.setId(105L); stored.setSequenceNumber(5);
        when(samples.findById(105L)).thenReturn(Optional.of(stored));
        service.sample(7L, "o1", "c1", sample(3, null, null)); // 迟到的补传不覆盖最新
        assertThat(c.getLatestSampleId()).isEqualTo(105L);
        service.sample(7L, "o1", "c1", sample(9, null, null));
        assertThat(c.getLatestSampleId()).isEqualTo(109L);
    }

    @Test void sampleRetryWithSamePayloadIsIdempotentAndDifferentPayloadConflicts() {
        existing("c1", null);
        var dto = sample(1, 30.0, 120.0);
        var captured = new FlightSample[1];
        when(samples.findByConnectionIdAndSequenceNumber("c1", 1L)).thenReturn(Optional.empty());
        when(samples.saveAndFlush(any())).thenAnswer(inv -> { captured[0] = inv.getArgument(0); captured[0].setId(1L); return captured[0]; });
        service.sample(7L, "o1", "c1", dto);
        when(samples.findByConnectionIdAndSequenceNumber("c1", 1L)).thenReturn(Optional.of(captured[0]));
        assertThat(service.sample(7L, "o1", "c1", dto).sampleId()).isEqualTo(1L);
        verify(samples, times(1)).saveAndFlush(any());
        var changed = new FlightSampleDTO(1, dto.capturedAt(), 31.0, 120.0, 100.0, 5.0, 80, true);
        assertStatus(catchThrowable(() -> service.sample(7L, "o1", "c1", changed)), HttpStatus.CONFLICT);
    }

    @Test void sampleRejectsInvalidCoordinatesClosedConnectionAndForeignOrder() {
        existing("c1", null);
        assertStatus(catchThrowable(() -> service.sample(7L, "o1", "c1", sample(1, 30.0, null))), HttpStatus.BAD_REQUEST);
        assertStatus(catchThrowable(() -> service.sample(7L, "o1", "c1",
                new FlightSampleDTO(1, 1, 30.0, 120.0, Double.NaN, null, null, null))), HttpStatus.BAD_REQUEST);
        assertStatus(catchThrowable(() -> service.sample(7L, "o1", "nope", sample(1, null, null))), HttpStatus.NOT_FOUND);
        var other = existing("c2", null); other.setOrderId("o2");
        assertStatus(catchThrowable(() -> service.sample(7L, "o1", "c2", sample(1, null, null))), HttpStatus.NOT_FOUND);
        existing("c3", LocalDateTime.now());
        assertStatus(catchThrowable(() -> service.sample(7L, "o1", "c3", sample(1, null, null))), HttpStatus.CONFLICT);
        order.setStage("COMPLETED");
        assertStatus(catchThrowable(() -> service.sample(7L, "o1", "c1", sample(1, null, null))), HttpStatus.CONFLICT);
    }

    @Test void onlyOwningPilotCanCloseAndClosingIsIdempotent() {
        var c = existing("c1", null); c.setActiveOrderId("o1"); c.setActiveSerial("SN1");
        assertStatus(catchThrowable(() -> service.close(8L, "o1", "c1")), HttpStatus.FORBIDDEN);
        service.close(7L, "o1", "c1");
        assertThat(c.getEndReason()).isEqualTo("DISCONNECTED"); assertThat(c.getActiveSerial()).isNull();
        service.close(7L, "o1", "c1");
        verify(live, times(1)).closeConnection("c1");
    }

    @Test void currentHidesOrderFromStrangersAndMarksFreshnessAndHistory() {
        assertStatus(catchThrowable(() -> service.current(99L, 1, "o1")), HttpStatus.NOT_FOUND);
        when(connections.findTopByOrderIdOrderByOpenedAtDescIdDesc("o1")).thenReturn(Optional.empty());
        when(connections.findByOrderIdOrderByOpenedAtAscIdAsc("o1")).thenReturn(List.of());
        when(samples.lastPositions(eq("o1"), any())).thenReturn(List.of());
        var empty = service.current(10L, 1, "o1");
        assertThat(empty.stale()).isTrue(); assertThat(empty.connection()).isNull();

        var c = existing("c1", null); c.setLatestSampleId(5L);
        var s = new FlightSample(); s.setId(5L); s.setConnectionId("c1"); s.setReceivedAt(LocalDateTime.now());
        s.setCapturedAt(System.currentTimeMillis() - 1000); s.setLatitude(30.0); s.setLongitude(120.0);
        when(samples.findById(5L)).thenReturn(Optional.of(s));
        when(samples.lastPositions(eq("o1"), any())).thenReturn(List.of(s));
        when(connections.findTopByOrderIdOrderByOpenedAtDescIdDesc("o1")).thenReturn(Optional.of(c));
        when(connections.findByOrderIdOrderByOpenedAtAscIdAsc("o1")).thenReturn(List.of(c));
        var fresh = service.current(null, 2, "o1"); // 管理员监管
        assertThat(fresh.stale()).isFalse(); assertThat(fresh.lastPosition().latitude()).isEqualTo(30.0);
        assertThat(fresh.connectionHistory()).hasSize(1);
        s.setCapturedAt(System.currentTimeMillis() - 60_000); // 延迟补传的采样不能当作实时
        assertThat(service.current(7L, 1, "o1").stale()).isTrue();
        s.setCapturedAt(System.currentTimeMillis() + 60_000); // 时钟超前
        assertThat(service.current(7L, 1, "o1").stale()).isTrue();
    }

    @Test void trackPagesByCursor() {
        var s = new FlightSample(); s.setId(42L); s.setConnectionId("c1");
        when(samples.findByOrderIdAndIdGreaterThanOrderByIdAsc(eq("o1"), eq(0L), any()))
                .thenReturn(new PageImpl<>(List.of(s), PageRequest.of(0, 1), 5));
        var page = service.track(10L, 1, "o1", 0);
        assertThat(page.nextAfter()).isEqualTo(42L); assertThat(page.hasNext()).isTrue();
        when(samples.findByOrderIdAndIdGreaterThanOrderByIdAsc(eq("o1"), eq(42L), any())).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.track(10L, 1, "o1", 42).nextAfter()).isEqualTo(42L);
        assertStatus(catchThrowable(() -> service.track(99L, 1, "o1", 0)), HttpStatus.NOT_FOUND);
    }
}

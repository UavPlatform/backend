package com.uav.flight.service;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.flight.mapper.FlightSampleRepository;
import com.uav.flight.pojo.dto.FlightConnectDTO;
import com.uav.flight.pojo.dto.FlightSampleBatchDTO;
import com.uav.flight.pojo.dto.FlightSampleDTO;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.flight.pojo.entity.FlightSample;
import com.uav.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ADR-0009：批量/断网补传、按设备+采集时间去重、载重字段、历史回放分页、执飞无人机写回订单。 */
class FlightTelemetryBatchTest {
    private final DispatchOrderRepository orders = mock(DispatchOrderRepository.class);
    private final FlightConnectionRepository connections = mock(FlightConnectionRepository.class);
    private final FlightSampleRepository samples = mock(FlightSampleRepository.class);
    private final AircraftModelRepository models = mock(AircraftModelRepository.class);
    private final FlightTelemetryBroadcaster broadcaster = mock(FlightTelemetryBroadcaster.class);
    private final FlightRecordService service = new FlightRecordService(orders, connections, samples, models,
            mock(DispatchLiveService.class), broadcaster);
    private final List<FlightSample> stored = new ArrayList<>();
    private DispatchOrder order;
    private FlightConnection connection;

    @BeforeEach void setUp() {
        order = new DispatchOrder(); order.setId("o1"); order.setUserId(10L); order.setAssignedPilotId(7L);
        order.setAssignmentVersion(2); order.setStage("IN_TRANSIT"); order.setFieldsJson("{}");
        when(orders.lockOrder("o1")).thenReturn(Optional.of(order));
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        connection = new FlightConnection(); connection.setId("c1"); connection.setOrderId("o1"); connection.setPilotId(7L);
        connection.setAssignmentVersion(2); connection.setSerialNumber("SN1"); connection.setModelCode("FC30");
        connection.setModelName("DJI FlyCart 30"); connection.setOpenedAt(LocalDateTime.now().minusMinutes(5));
        when(connections.findById("c1")).thenReturn(Optional.of(connection));
        when(samples.findByConnectionIdAndSequenceNumber(any(), anyLong())).thenAnswer(inv -> stored.stream()
                .filter(s -> s.getSequenceNumber() == (long) inv.getArgument(1)).findFirst());
        when(samples.findBySerialNumberAndCapturedAt(any(), anyLong())).thenAnswer(inv -> stored.stream()
                .filter(s -> s.getCapturedAt() == (long) inv.getArgument(1)).findFirst());
        when(samples.saveAndFlush(any())).thenAnswer(inv -> {
            FlightSample s = inv.getArgument(0); s.setId((long) stored.size() + 1); stored.add(s); return s;
        });
        when(samples.findById(anyLong())).thenAnswer(inv -> stored.stream().filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
    }

    private static FlightSampleDTO dto(long seq, long at, Double payload) {
        return new FlightSampleDTO(seq, at, 30.0, 120.0, 50.0, 3.0, 70, true, payload);
    }

    @Test void batchStoresNewSkipsDuplicatesRejectsInvalidAndAdvancesLatestOnce() {
        long now = System.currentTimeMillis();
        service.sample(7L, "o1", "c1", dto(0, now - 4000, 12.5));
        var result = service.batch(7L, "o1", "c1", new FlightSampleBatchDTO(List.of(
                dto(2, now - 2000, null), dto(1, now - 3000, 12.0), dto(0, now - 4000, 12.5),
                new FlightSampleDTO(3, now - 1000, 30.0, null, null, null, null, null, null),
                dto(4, now - 86_400_000L, null), dto(5, now - 4000, 99.0))));
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(3);
        assertThat(stored).extracting(FlightSample::getSerialNumber).containsOnly("SN1");
        assertThat(stored.getFirst().getPayloadKg()).isEqualTo(12.5);
        assertThat(connection.getLatestSampleId()).isEqualTo(stored.stream().filter(s -> s.getSequenceNumber() == 2).findFirst().orElseThrow().getId());
        verify(broadcaster, times(2)).publish(eq(connection), any());
    }

    @Test void endedConnectionAcceptsBackfillWithoutTouchingRealtimeState() {
        connection.setEndedAt(LocalDateTime.now()); order.setStage("DELIVERY_CONFIRMATION");
        var result = service.batch(7L, "o1", "c1", new FlightSampleBatchDTO(List.of(dto(9, System.currentTimeMillis() - 1000, null))));
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(connection.getLatestSampleId()).isNull();
        verifyNoInteractions(broadcaster);
    }

    @Test void batchRefusesCancelledOrdersAndOtherPilots() {
        order.setStage("CANCELLED");
        var batch = new FlightSampleBatchDTO(List.of(dto(1, System.currentTimeMillis(), null)));
        assertThatThrownBy(() -> service.batch(7L, "o1", "c1", batch)).isInstanceOf(BusinessException.class)
                .extracting("httpStatus").isEqualTo(HttpStatus.CONFLICT);
        order.setStage("IN_TRANSIT");
        assertThatThrownBy(() -> service.batch(8L, "o1", "c1", batch)).isInstanceOf(BusinessException.class);
    }

    @Test void historyPagesByCaptureTimeAndValidatesRange() {
        var a = new FlightSample(); a.setId(1L); a.setConnectionId("c1"); a.setCapturedAt(100); a.setReceivedAt(LocalDateTime.now());
        var b = new FlightSample(); b.setId(2L); b.setConnectionId("c1"); b.setCapturedAt(200); b.setReceivedAt(LocalDateTime.now());
        when(samples.history(eq("o1"), eq(0L), eq(500L), anyLong(), anyLong(), any())).thenReturn(List.of(a, b));
        when(connections.findByOrderIdOrderByOpenedAtAscIdAsc("o1")).thenReturn(List.of(connection));
        var page = service.history(10L, 0, "o1", 0L, 500L, null, null, 1);
        assertThat(page.items()).hasSize(1); assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCapturedAt()).isEqualTo(100L); assertThat(page.nextSampleId()).isEqualTo(1L);
        assertThat(page.connections()).hasSize(1);
        assertThatThrownBy(() -> service.history(10L, 0, "o1", 5L, 1L, null, null, 10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.history(10L, 0, "o1", null, null, null, null, 1001)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.history(99L, 0, "o1", null, null, null, null, 10)).isInstanceOf(BusinessException.class);
        when(samples.history(eq("o1"), eq(0L), eq(Long.MAX_VALUE), anyLong(), anyLong(), any())).thenReturn(List.of());
        var empty = service.history(1L, 2, "o1", null, null, null, null, 10);
        assertThat(empty.items()).isEmpty(); assertThat(empty.nextCapturedAt()).isNull(); assertThat(empty.hasNext()).isFalse();
    }

    @Test void connectWritesDjiflyReportedDroneOntoTheOrder() {
        order.setStage("PILOT_ARRIVED");
        when(connections.findByPilotIdAndRequestKey(any(), any())).thenReturn(Optional.empty());
        when(connections.findByActiveOrderId(any())).thenReturn(Optional.empty());
        when(models.findAll()).thenReturn(List.of());
        service.connect(7L, "o1", new FlightConnectDTO(2, "rk", "SN9", "FC30", "DJI FlyCart 30"));
        assertThat(order.getExecutedSerialNumber()).isEqualTo("SN9");
        assertThat(order.getExecutedModelCode()).isEqualTo("FC30");
        assertThat(order.getExecutedModelName()).isEqualTo("DJI FlyCart 30");
        verify(orders).save(order);
    }
}

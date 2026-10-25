package com.uav.dispatch;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.DispatchQuoteVO;
import com.uav.dispatch.service.DispatchActualSettlement;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.flight.service.FlightRecordService;
import com.uav.server.config.DispatchBillingConfig;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ADR-0007 #4：尾款按实际执飞机型与实际连机时长结算；无连机记录时保留预估并标注。 */
class DispatchActualSettlementTest {
    private final FlightRecordService flight = mock(FlightRecordService.class);
    private final AircraftModelRepository models = mock(AircraftModelRepository.class);
    private final DispatchBillingConfig config = new DispatchBillingConfig();
    private final DispatchActualSettlement settlement = new DispatchActualSettlement(flight, models, config);
    private final DispatchQuoteVO quote = new DispatchQuoteVO("DISTANCE_V1", "START_END_GEODESIC", new BigDecimal("1000"),
            new BigDecimal("0.10"), new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("200.00"), new BigDecimal("100.00"));
    private final LocalDateTime t0 = LocalDateTime.of(2026, 10, 25, 10, 0);

    private static FlightConnection c(String serial, Long modelId, LocalDateTime open, LocalDateTime end) {
        var c = new FlightConnection(); c.setSerialNumber(serial); c.setModelCode("M-" + serial); c.setModelName("Model " + serial);
        c.setAircraftModelId(modelId); c.setOpenedAt(open); c.setEndedAt(end); return c;
    }
    private DispatchOrder order() { var o = new DispatchOrder(); o.setId("o1"); return o; }

    @Test void noConnectionKeepsEstimateAndSaysSo() {
        when(flight.connectionsOf("o1")).thenReturn(List.of());
        var o = order();
        assertThat(settlement.settle(o, quote, t0)).isEqualByComparingTo("200.00");
        assertThat(o.getSettlementBasis()).isEqualTo(DispatchActualSettlement.ESTIMATE_FALLBACK);
        assertThat(o.getActualFlightSeconds()).isNull();
    }

    @Test void sumsAllConnectionsAndPricesTheLongestDronesModel() {
        var model = new AircraftModel(); model.setId(3L); model.setCoefficient(new BigDecimal("1.500"));
        when(models.findById(3L)).thenReturn(Optional.of(model));
        when(flight.connectionsOf("o1")).thenReturn(List.of(
                c("A", null, t0, t0.plusMinutes(4)),
                c("B", 3L, t0.plusMinutes(5), null), // still open: ends at delivery time
                c("C", null, t0.plusMinutes(30), t0.plusMinutes(29)))); // clock skew never goes negative
        var o = order();
        var amount = settlement.settle(o, quote, t0.plusMinutes(15).plusSeconds(1));
        // 4 min + 10 min 1 s = 841 s → 15 billable minutes × 5.00 × 1.5 = 112.50 + pilot fee 100.00
        assertThat(amount).isEqualByComparingTo("212.50");
        assertThat(o.getActualFlightSeconds()).isEqualTo(841L);
        assertThat(o.getExecutedSerialNumber()).isEqualTo("B");
        assertThat(o.getExecutedAircraftModelId()).isEqualTo(3L);
        assertThat(o.getSettlementBasis()).isEqualTo(DispatchActualSettlement.ACTUAL);
        assertThat(o.getFinalAmount()).isEqualByComparingTo("212.50");
    }

    @Test void shortOrUnknownModelFlightsChargeTheConfiguredMinimum() {
        config.setRatePerMinute(new BigDecimal("2.00")); config.setMinimumEquipmentCharge(new BigDecimal("30.00"));
        when(models.findById(9L)).thenReturn(Optional.empty());
        when(flight.connectionsOf("o1")).thenReturn(List.of(c("A", 9L, t0, t0.plusSeconds(20))));
        var o = order();
        assertThat(settlement.settle(o, quote, t0)).isEqualByComparingTo("130.00");
        assertThat(config.getRatePerMinute()).isEqualByComparingTo("2.00");
        assertThat(o.getActualFlightSeconds()).isEqualTo(20L);
    }
}

package com.uav.dispatch.service;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.DispatchQuoteVO;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.flight.service.FlightRecordService;
import com.uav.server.config.DispatchBillingConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * ADR-0007 #4/#6/#9: settle by the drone djifly actually connected and the actual connected flight time,
 * not by the estimate. Without any connection record the estimate is kept and marked as such.
 */
@Service
public class DispatchActualSettlement {
    public static final String ACTUAL = "ACTUAL_FLIGHT_V1";
    public static final String ESTIMATE_FALLBACK = "ESTIMATE_NO_FLIGHT_RECORD";
    private final FlightRecordService flight;
    private final AircraftModelRepository models;
    private final DispatchBillingConfig config;

    public DispatchActualSettlement(FlightRecordService flight, AircraftModelRepository models, DispatchBillingConfig config) {
        this.flight = flight; this.models = models; this.config = config;
    }

    /** Writes executed drone, flight seconds, basis and final amount onto the order and returns the final amount. */
    public BigDecimal settle(DispatchOrder order, DispatchQuoteVO quote, LocalDateTime end) {
        var records = flight.connectionsOf(order.getId());
        if (records.isEmpty()) {
            order.setSettlementBasis(ESTIMATE_FALLBACK);
            order.setFinalAmount(quote.totalAmount());
            return quote.totalAmount();
        }
        long total = 0, longest = -1;
        FlightConnection primary = null;
        for (var c : records) {
            long seconds = Math.max(0, Duration.between(c.getOpenedAt(), c.getEndedAt() != null ? c.getEndedAt() : end).getSeconds());
            total += seconds;
            if (seconds > longest) { longest = seconds; primary = c; }
        }
        BigDecimal coefficient = primary.getAircraftModelId() == null ? BigDecimal.ONE
                : models.findById(primary.getAircraftModelId()).map(AircraftModel::getCoefficient).orElse(BigDecimal.ONE);
        long minutes = Math.max(1, (total + 59) / 60);
        BigDecimal equipment = config.getRatePerMinute().multiply(BigDecimal.valueOf(minutes)).multiply(coefficient)
                .max(config.getMinimumEquipmentCharge()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = equipment.add(quote.pilotServiceFee()).setScale(2, RoundingMode.HALF_UP);
        order.setExecutedSerialNumber(primary.getSerialNumber()); order.setExecutedModelCode(primary.getModelCode());
        order.setExecutedModelName(primary.getModelName()); order.setExecutedAircraftModelId(primary.getAircraftModelId());
        order.setActualFlightSeconds(total); order.setSettlementBasis(ACTUAL); order.setFinalAmount(amount);
        return amount;
    }
}

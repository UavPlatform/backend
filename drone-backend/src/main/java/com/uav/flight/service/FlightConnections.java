package com.uav.flight.service;

import com.uav.flight.mapper.FlightConnectionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Closes any still-open v1 connection record when an order reaches a terminal business state.
 *
 * <p>Connection registration was retired with ADR-0014, so no new rows appear; the remaining ones are
 * historical and must not stay open forever. This is bookkeeping only: it never controls physical flight.
 */
@Service
public class FlightConnections {
    private final FlightConnectionRepository connections;

    public FlightConnections(FlightConnectionRepository connections) {
        this.connections = connections;
    }

    public void closeActive(String orderId, String reason) {
        connections.findByActiveOrderId(orderId).ifPresent(connection -> {
            if (connection.getEndedAt() != null) return;
            connection.setEndedAt(LocalDateTime.now());
            connection.setEndReason(reason);
            connection.setActiveOrderId(null);
            connection.setActiveSerial(null);
            connections.save(connection);
        });
        connections.flush();
    }
}

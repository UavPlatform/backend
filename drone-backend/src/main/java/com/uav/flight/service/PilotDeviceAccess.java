package com.uav.flight.service;

import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.user.mapper.RiderUavRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Whether a pilot may use a device (device channel handshake, pilot start live).
 * ADR-0007 (2026-10-22): the drone belongs to the dispatch task, not the pilot, so a device that is the active
 * flight connection of the pilot's own dispatch task is allowed without a manual binding. The legacy
 * rider_uav binding (old matching flow) is still accepted.
 */
@Component
public class PilotDeviceAccess {
    private final RiderUavRepository riderUav;
    private final FlightConnectionRepository connections;

    public PilotDeviceAccess(RiderUavRepository riderUav, FlightConnectionRepository connections) {
        this.riderUav = riderUav; this.connections = connections;
    }

    public boolean mayUse(Long pilot, String deviceId) {
        if (pilot == null || deviceId == null || deviceId.isBlank()) return false;
        if (riderUav.existsByUserIdAndDjiId(pilot, deviceId)) return true;
        return connections.findByActiveSerial(com.uav.server.util.SerialNumbers.normalize(deviceId))
                .filter(c -> pilot.equals(c.getPilotId())).isPresent();
    }
}

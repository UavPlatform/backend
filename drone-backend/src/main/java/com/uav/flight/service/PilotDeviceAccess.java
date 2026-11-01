package com.uav.flight.service;

import com.uav.equipment.mapper.DroneBorrowRepository;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.equipment.pojo.entity.DroneBorrow;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.server.util.SerialNumbers;
import org.springframework.stereotype.Component;

/**
 * Whether a pilot may use a device (device channel handshake, pilot start live).
 * ADR-0007 / GOV-007: active dispatch flight connection or approved platform borrow.
 * Pilot self-owned rider_uav binding has been removed.
 */
@Component
public class PilotDeviceAccess {
    private final FlightConnectionRepository connections;
    private final ProviderDroneRepository drones;
    private final DroneBorrowRepository borrows;

    public PilotDeviceAccess(FlightConnectionRepository connections, ProviderDroneRepository drones,
                             DroneBorrowRepository borrows) {
        this.connections = connections; this.drones = drones; this.borrows = borrows;
    }

    public boolean mayUse(Long pilot, String deviceId) {
        if (pilot == null || deviceId == null || deviceId.isBlank()) return false;
        String serial = SerialNumbers.normalize(deviceId);
        if (connections.findByActiveSerial(serial).filter(c -> pilot.equals(c.getPilotId())).isPresent()) {
            return true;
        }
        var drone = drones.findBySerialNumber(serial).orElse(null);
        if (drone == null || drone.getCurrentBorrowId() == null) return false;
        return borrows.findById(drone.getCurrentBorrowId())
                .filter(b -> DroneBorrow.APPROVED.equals(b.getStatus()))
                .filter(b -> pilot.equals(b.getPilotId()))
                .isPresent();
    }
}

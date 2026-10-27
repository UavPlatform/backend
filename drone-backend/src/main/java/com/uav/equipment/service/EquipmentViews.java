package com.uav.equipment.service;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.airport.mapper.AirportRepository;
import com.uav.equipment.mapper.*;
import com.uav.equipment.pojo.entity.*;
import com.uav.equipment.pojo.vo.*;
import org.springframework.stereotype.Component;

@Component
public class EquipmentViews {
    private final ProviderDroneRepository drones;
    private final DroneBorrowRepository borrows;
    private final AircraftModelRepository models;
    private final AirportRepository airports;
    public EquipmentViews(ProviderDroneRepository drones, DroneBorrowRepository borrows,
                          AircraftModelRepository models, AirportRepository airports) {
        this.drones = drones; this.borrows = borrows; this.models = models; this.airports = airports;
    }
    ProviderDroneVO drone(ProviderDrone d, Long pilot, boolean management) {
        boolean mine = d.getCurrentBorrowId() != null && borrows.findById(d.getCurrentBorrowId()).map(b -> b.getPilotId().equals(pilot)).orElse(false);
        var airport = airports.findById(d.getAirportId()).orElse(null);
        return new ProviderDroneVO(d.getId(),
                d.getAircraftModelId(), models.findById(d.getAircraftModelId()).orElseThrow(EquipmentAccess::missing).getDisplayName(),
                d.getSerialNumber(), d.getAirportId(), airport == null ? null : airport.getName(), d.isEnabled(), d.getCurrentBorrowId() != null,
                mine, management || mine ? d.getCurrentBorrowId() : null);
    }
    DroneBorrowVO borrow(DroneBorrow b) {
        return new DroneBorrowVO(b.getId(), drone(drones.findById(b.getDroneId()).orElseThrow(EquipmentAccess::missing), b.getPilotId(), false),
                b.getPilotId(), b.getBorrowedAt(), b.getReturnedAt());
    }
}

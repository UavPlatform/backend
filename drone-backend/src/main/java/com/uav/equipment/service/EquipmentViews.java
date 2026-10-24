package com.uav.equipment.service;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.equipment.mapper.*;
import com.uav.equipment.pojo.entity.*;
import com.uav.equipment.pojo.vo.*;
import org.springframework.stereotype.Component;

@Component
public class EquipmentViews {
    private final EquipmentProviderRepository providers;
    private final ProviderDroneRepository drones;
    private final DroneBorrowRepository borrows;
    private final AircraftModelRepository models;
    public EquipmentViews(EquipmentProviderRepository providers, ProviderDroneRepository drones, DroneBorrowRepository borrows, AircraftModelRepository models) {
        this.providers = providers; this.drones = drones; this.borrows = borrows; this.models = models;
    }
    ProviderDroneVO drone(ProviderDrone d, Long pilot, boolean management) {
        boolean mine = d.getCurrentBorrowId() != null && borrows.findById(d.getCurrentBorrowId()).map(b -> b.getPilotId().equals(pilot)).orElse(false);
        return new ProviderDroneVO(d.getId(), d.getProviderId(), providers.findById(d.getProviderId()).orElseThrow(EquipmentAccess::missing).getDisplayName(),
                d.getAircraftModelId(), models.findById(d.getAircraftModelId()).orElseThrow(EquipmentAccess::missing).getDisplayName(),
                d.getSerialNumber(), d.getAirportName(), d.getLatitude(), d.getLongitude(), d.isEnabled(), d.getCurrentBorrowId() != null,
                mine, management || mine ? d.getCurrentBorrowId() : null);
    }
    DroneBorrowVO borrow(DroneBorrow b) {
        return new DroneBorrowVO(b.getId(), drone(drones.findById(b.getDroneId()).orElseThrow(EquipmentAccess::missing), b.getPilotId(), false),
                b.getPilotId(), b.getBorrowedAt(), b.getReturnedAt());
    }
}

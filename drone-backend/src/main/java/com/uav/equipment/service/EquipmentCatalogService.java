package com.uav.equipment.service;

import com.uav.aircraft.service.AircraftModelService;
import com.uav.equipment.mapper.*;
import com.uav.equipment.pojo.dto.ProviderDroneDTO;
import com.uav.equipment.pojo.entity.ProviderDrone;
import com.uav.equipment.pojo.vo.*;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/** REQ-SUP-GOV-005 / REQ-PILOT-EXEC-005: platform-owned rental drones registered by admins (ADR-0011). */
@Service
public class EquipmentCatalogService {
    private final ProviderDroneRepository drones;
    private final AircraftModelService models;
    private final EquipmentViews views;
    private final DroneBorrowRepository borrows;
    public EquipmentCatalogService(ProviderDroneRepository drones, AircraftModelService models, EquipmentViews views, DroneBorrowRepository borrows) {
        this.drones = drones; this.models = models; this.views = views; this.borrows = borrows;
    }
    @Transactional
    public ProviderDrone register(ProviderDroneDTO input) {
        models.requireSelectable(input.aircraftModelId());
        String serial = serial(input);
        if (drones.existsBySerialNumber(serial)) throw EquipmentAccess.conflict("机身号已登记");
        var d = new ProviderDrone(); d.setId(UUID.randomUUID().toString());
        d.setAircraftModelId(input.aircraftModelId()); d.setSerialNumber(serial); d.setAirportName(input.airportName().trim());
        d.setLatitude(input.latitude()); d.setLongitude(input.longitude()); d.setCreatedAt(LocalDateTime.now());
        try { return drones.saveAndFlush(d); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw EquipmentAccess.conflict("机身号已登记"); }
    }
    @Transactional
    public ProviderDrone update(String id, ProviderDroneDTO input) {
        var d = drones.lockDrone(id).orElseThrow(EquipmentAccess::missing);
        String serial = serial(input);
        boolean identityChanged = !serial.equals(d.getSerialNumber()) || !input.aircraftModelId().equals(d.getAircraftModelId());
        if (identityChanged && d.getCurrentBorrowId() != null) throw EquipmentAccess.conflict("设备借出期间不能更改机身号或机型");
        if (identityChanged && borrows.existsByDroneId(id)) throw EquipmentAccess.conflict("设备已有借还记录，不能更改机身号或机型");
        if (!serial.equals(d.getSerialNumber()) && drones.existsBySerialNumber(serial)) throw EquipmentAccess.conflict("机身号已登记");
        if (!input.aircraftModelId().equals(d.getAircraftModelId())) models.requireSelectable(input.aircraftModelId());
        d.setSerialNumber(serial); d.setAircraftModelId(input.aircraftModelId()); d.setAirportName(input.airportName().trim());
        d.setLatitude(input.latitude()); d.setLongitude(input.longitude());
        try { return drones.saveAndFlush(d); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw EquipmentAccess.conflict("机身号已登记"); }
    }
    private String serial(ProviderDroneDTO input) {
        String value = com.uav.server.util.SerialNumbers.normalize(input.serialNumber());
        if (value == null || value.isBlank() || value.length() > com.uav.server.util.SerialNumbers.MAX_LENGTH) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        return value;
    }
    @Transactional
    public ProviderDrone enabled(String id, boolean enabled) {
        var d = drones.lockDrone(id).orElseThrow(EquipmentAccess::missing);
        d.setEnabled(enabled); return drones.saveAndFlush(d);
    }
    @Transactional(readOnly = true)
    public ProviderDronePageVO catalog(Long pilot, int page) {
        var rows = drones.catalog(EquipmentAccess.page(page, "createdAt"));
        return new ProviderDronePageVO(rows.stream().map(d -> views.drone(d, pilot, false)).toList(), page, rows.hasNext());
    }
}

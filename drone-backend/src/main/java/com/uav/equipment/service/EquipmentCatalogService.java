package com.uav.equipment.service;

import com.uav.aircraft.service.AircraftModelService;
import com.uav.equipment.mapper.*;
import com.uav.equipment.pojo.dto.ProviderDroneDTO;
import com.uav.equipment.pojo.entity.ProviderDrone;
import com.uav.equipment.pojo.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/** REQ-PROV-GOV-001 / REQ-PILOT-EXEC-005: registered inventory, independent of legacy device bindings. */
@Service
public class EquipmentCatalogService {
    private final ProviderDroneRepository drones;
    private final EquipmentProviderService providers;
    private final AircraftModelService models;
    private final EquipmentViews views;
    private final DroneBorrowRepository borrows;
    public EquipmentCatalogService(ProviderDroneRepository drones, EquipmentProviderService providers, AircraftModelService models, EquipmentViews views, DroneBorrowRepository borrows) {
        this.drones = drones; this.providers = providers; this.models = models; this.views = views; this.borrows = borrows;
    }
    @Transactional
    public ProviderDroneVO register(Long provider, ProviderDroneDTO input) {
        providers.lockEnabled(provider); models.requireSelectable(input.aircraftModelId());
        String serial = serial(input);
        if (drones.existsBySerialNumber(serial)) throw EquipmentAccess.conflict("机身号已登记");
        var d = new ProviderDrone(); d.setId(UUID.randomUUID().toString()); d.setProviderId(provider);
        d.setAircraftModelId(input.aircraftModelId()); d.setSerialNumber(serial); d.setAirportName(input.airportName().trim());
        d.setLatitude(input.latitude()); d.setLongitude(input.longitude()); d.setCreatedAt(LocalDateTime.now());
        try { return views.drone(drones.saveAndFlush(d), null, true); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw EquipmentAccess.conflict("机身号已登记"); }
    }
    @Transactional
    public ProviderDroneVO update(Long provider, String id, ProviderDroneDTO input) {
        providers.lockEnabled(provider);
        var d = drones.lockDrone(id).filter(row -> row.getProviderId().equals(provider)).orElseThrow(EquipmentAccess::missing);
        if (d.getCurrentBorrowId() != null) throw EquipmentAccess.conflict("设备借出期间不可修改登记资料");
        String serial = serial(input);
        boolean identityChanged = !serial.equals(d.getSerialNumber()) || !input.aircraftModelId().equals(d.getAircraftModelId());
        if (identityChanged && borrows.existsByDroneId(id)) throw EquipmentAccess.conflict("设备已有借还记录，不能更改机身号或机型");
        if (!serial.equals(d.getSerialNumber()) && drones.existsBySerialNumber(serial)) throw EquipmentAccess.conflict("机身号已登记");
        models.requireSelectable(input.aircraftModelId());
        d.setSerialNumber(serial); d.setAircraftModelId(input.aircraftModelId()); d.setAirportName(input.airportName().trim());
        d.setLatitude(input.latitude()); d.setLongitude(input.longitude());
        try { return views.drone(drones.saveAndFlush(d), null, true); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw EquipmentAccess.conflict("机身号已登记"); }
    }
    private String serial(ProviderDroneDTO input) {
        String value = input.serialNumber().trim().toUpperCase(Locale.ROOT);
        if (value.isBlank() || value.length() > 64) throw new com.uav.server.exception.BusinessException(com.uav.server.enums.ApiErrorCode.INVALID_PARAM);
        return value;
    }
    @Transactional
    public ProviderDroneVO enabled(Long provider, String id, boolean enabled) {
        providers.lockEnabled(provider);
        var d = drones.lockDrone(id).filter(row -> row.getProviderId().equals(provider)).orElseThrow(EquipmentAccess::missing);
        d.setEnabled(enabled); return views.drone(drones.saveAndFlush(d), null, true);
    }
    @Transactional(readOnly = true)
    public ProviderDronePageVO mine(Long provider, int page) {
        providers.requireEnabled(provider);
        var rows = drones.findByProviderId(provider, EquipmentAccess.page(page, "createdAt"));
        return new ProviderDronePageVO(rows.stream().map(d -> views.drone(d, null, true)).toList(), page, rows.hasNext());
    }
    @Transactional(readOnly = true)
    public ProviderDronePageVO catalog(Long pilot, Long provider, int page) {
        var rows = drones.catalog(provider, EquipmentAccess.page(page, "createdAt"));
        return new ProviderDronePageVO(rows.stream().map(d -> views.drone(d, pilot, false)).toList(), page, rows.hasNext());
    }
    @Transactional(readOnly = true)
    public ProviderDronePageVO admin(int page) {
        var rows = drones.findAll(EquipmentAccess.page(page, "createdAt"));
        return new ProviderDronePageVO(rows.stream().map(d -> views.drone(d, null, true)).toList(), page, rows.hasNext());
    }
}

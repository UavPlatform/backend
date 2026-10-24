package com.uav.equipment.service;

import com.uav.aircraft.service.AircraftModelService;
import com.uav.equipment.mapper.*;
import com.uav.equipment.pojo.dto.DroneBorrowDTO;
import com.uav.equipment.pojo.entity.DroneBorrow;
import com.uav.equipment.pojo.vo.*;
import com.uav.user.pojo.entity.User;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

/** REQ-PILOT-EXEC-005: custody only. No order, rental charge, deposit, or aircraft binding is created. */
@Service
public class DroneBorrowService {
    private final ProviderDroneRepository drones;
    private final DroneBorrowRepository borrows;
    private final EquipmentProviderService providers;
    private final AircraftModelService models;
    private final EquipmentViews views;
    private final EntityManager em;
    public DroneBorrowService(ProviderDroneRepository drones, DroneBorrowRepository borrows, EquipmentProviderService providers,
            AircraftModelService models, EquipmentViews views, EntityManager em) {
        this.drones = drones; this.borrows = borrows; this.providers = providers; this.models = models; this.views = views; this.em = em;
    }
    @Transactional
    public DroneBorrowVO borrow(Long pilot, DroneBorrowDTO input) {
        var actor = em.find(User.class, pilot, LockModeType.PESSIMISTIC_WRITE);
        if (actor == null || !Integer.valueOf(1).equals(actor.getRole())) throw EquipmentAccess.missing();
        var existing = borrows.findByPilotIdAndRequestKey(pilot, input.requestKey());
        if (existing.isPresent()) {
            if (!existing.get().getDroneId().equals(input.droneId())) throw EquipmentAccess.conflict("借用标识已用于其他设备");
            return views.borrow(existing.get());
        }
        var snapshot = drones.findById(input.droneId()).orElseThrow(EquipmentAccess::missing);
        providers.lockEnabled(snapshot.getProviderId());
        var d = drones.lockDrone(input.droneId()).orElseThrow(EquipmentAccess::missing);
        em.refresh(d, LockModeType.PESSIMISTIC_WRITE);
        models.requireSelectable(d.getAircraftModelId());
        if (!d.isEnabled() || d.getCurrentBorrowId() != null) throw EquipmentAccess.conflict("设备已借出或已停用，请刷新列表");
        var b = new DroneBorrow(); b.setId(UUID.randomUUID().toString()); b.setDroneId(d.getId()); b.setPilotId(pilot);
        b.setRequestKey(input.requestKey()); b.setBorrowedAt(LocalDateTime.now()); borrows.saveAndFlush(b);
        d.setCurrentBorrowId(b.getId()); drones.saveAndFlush(d);
        return views.borrow(b);
    }
    @Transactional
    public DroneBorrowVO giveBack(Long pilot, String id) {
        var snapshot = borrows.findById(id).filter(b -> b.getPilotId().equals(pilot)).orElseThrow(EquipmentAccess::missing);
        var d = drones.lockDrone(snapshot.getDroneId()).orElseThrow(EquipmentAccess::missing);
        // A retry for an old completed loan must never return a newer loan of the same drone.
        em.refresh(snapshot);
        if (snapshot.getReturnedAt() != null) return views.borrow(snapshot);
        if (!id.equals(d.getCurrentBorrowId())) throw EquipmentAccess.conflict("设备当前借用记录已变化，请刷新");
        snapshot.setReturnedAt(LocalDateTime.now()); borrows.saveAndFlush(snapshot);
        d.setCurrentBorrowId(null); drones.saveAndFlush(d);
        return views.borrow(snapshot);
    }
    @Transactional(readOnly = true)
    public DroneBorrowPageVO mine(Long pilot, int page) {
        var rows = borrows.findByPilotId(pilot, EquipmentAccess.page(page, "borrowedAt"));
        return new DroneBorrowPageVO(rows.stream().map(views::borrow).toList(), page, rows.hasNext());
    }
    @Transactional(readOnly = true)
    public DroneBorrowPageVO provider(Long provider, int page) {
        providers.requireEnabled(provider);
        var rows = borrows.providerHistory(provider, EquipmentAccess.page(page, "borrowedAt"));
        return new DroneBorrowPageVO(rows.stream().map(views::borrow).toList(), page, rows.hasNext());
    }
    @Transactional(readOnly = true)
    public DroneBorrowPageVO admin(int page) {
        var rows = borrows.findAll(EquipmentAccess.page(page, "borrowedAt"));
        return new DroneBorrowPageVO(rows.stream().map(views::borrow).toList(), page, rows.hasNext());
    }
}

package com.uav.equipment.service;

import com.uav.aircraft.service.AircraftModelService;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
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

/**
 * REQ-PILOT-EXEC-005: pilot applies by model (optional order ref); approval creates custody.
 * Billing still uses djifly-reported SN (ADR-0007); this only tracks who holds the airframe.
 */
@Service
public class DroneBorrowService {
    private final ProviderDroneRepository drones;
    private final DroneBorrowRepository borrows;
    private final AircraftModelService models;
    private final EquipmentViews views;
    private final EquipmentNotificationService notices;
    private final DispatchOrderRepository orders;
    private final EntityManager em;

    public DroneBorrowService(ProviderDroneRepository drones, DroneBorrowRepository borrows,
            AircraftModelService models, EquipmentViews views, EquipmentNotificationService notices,
            DispatchOrderRepository orders, EntityManager em) {
        this.drones = drones; this.borrows = borrows; this.models = models; this.views = views;
        this.notices = notices; this.orders = orders; this.em = em;
    }

    @Transactional(readOnly = true)
    public ModelStockVO modelStock() {
        var enabled = models.listEnabled();
        var counts = drones.countInStockGroupedByModel().stream()
                .collect(java.util.stream.Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
        return new ModelStockVO(enabled.stream()
                .map(m -> new ModelStockVO.Item(m.getId(), m.getDisplayName(), m.getModelCode(), counts.getOrDefault(m.getId(), 0L)))
                .toList());
    }

    @Transactional
    public DroneBorrowVO apply(Long pilot, DroneBorrowDTO input) {
        var actor = em.find(User.class, pilot, LockModeType.PESSIMISTIC_WRITE);
        if (actor == null || !Integer.valueOf(1).equals(actor.getRole())) throw EquipmentAccess.missing();
        var existing = borrows.findByPilotIdAndRequestKey(pilot, input.requestKey());
        if (existing.isPresent()) {
            DroneBorrow row = existing.get();
            if (!row.getAircraftModelId().equals(input.aircraftModelId())) {
                throw EquipmentAccess.conflict("借用标识已用于其他机型申请");
            }
            boolean warn = borrows.countByPilotIdAndStatus(pilot, DroneBorrow.APPROVED) > 0
                    && !DroneBorrow.APPROVED.equals(row.getStatus());
            return views.borrow(row, warn);
        }
        models.requireSelectable(input.aircraftModelId());
        if (drones.countInStockByModel(input.aircraftModelId()) <= 0) {
            throw EquipmentAccess.conflict("该机型当前无在库启用飞机，无法申请");
        }
        if (input.orderId() != null && !input.orderId().isBlank()) {
            DispatchOrder order = orders.findById(input.orderId()).orElseThrow(() ->
                    EquipmentAccess.conflict("关联订单不存在"));
            if (order.getAssignedPilotId() == null || !order.getAssignedPilotId().equals(pilot)) {
                throw EquipmentAccess.conflict("只能关联本人进行中的订单作参考");
            }
        }
        boolean alreadyHas = borrows.countByPilotIdAndStatus(pilot, DroneBorrow.APPROVED) > 0;
        var b = new DroneBorrow();
        b.setId(UUID.randomUUID().toString());
        b.setPilotId(pilot);
        b.setAircraftModelId(input.aircraftModelId());
        b.setRequestKey(input.requestKey());
        b.setStatus(DroneBorrow.PENDING);
        b.setOrderId(blankToNull(input.orderId()));
        b.setAppliedAt(LocalDateTime.now());
        borrows.saveAndFlush(b);
        notices.notifyAdmins(b.getId(), EquipmentNotificationService.KIND_PENDING,
                "新的借机待审批", "飞手提交了借机申请，请指定机身号后通过或驳回。");
        return views.borrow(b, alreadyHas);
    }

    @Transactional
    public DroneBorrowVO giveBack(Long pilot, String id) {
        var snapshot = borrows.lockById(id).filter(b -> b.getPilotId().equals(pilot)).orElseThrow(EquipmentAccess::missing);
        if (DroneBorrow.RETURNED.equals(snapshot.getStatus()) && snapshot.getReturnedAt() != null) {
            return views.borrow(snapshot);
        }
        if (!DroneBorrow.APPROVED.equals(snapshot.getStatus()) || snapshot.getDroneId() == null) {
            throw EquipmentAccess.conflict("仅已借出的飞机可以归还");
        }
        var d = drones.lockDrone(snapshot.getDroneId()).orElseThrow(EquipmentAccess::missing);
        if (!id.equals(d.getCurrentBorrowId())) throw EquipmentAccess.conflict("设备当前借用记录已变化，请刷新");
        snapshot.setReturnedAt(LocalDateTime.now());
        snapshot.setStatus(DroneBorrow.RETURNED);
        borrows.saveAndFlush(snapshot);
        d.setCurrentBorrowId(null);
        drones.saveAndFlush(d);
        String sn = d.getSerialNumber();
        notices.notifyAdmins(snapshot.getId(), EquipmentNotificationService.KIND_RETURNED,
                "平台无人机已归还", "飞手已确认归还机身号 " + sn + "，无需再批。");
        return views.borrow(snapshot);
    }

    @Transactional(readOnly = true)
    public DroneBorrowPageVO mine(Long pilot, int page) {
        var rows = borrows.findByPilotId(pilot, EquipmentAccess.page(page, "appliedAt"));
        return new DroneBorrowPageVO(rows.stream().map(views::borrow).toList(), page, rows.hasNext());
    }

    @Transactional(readOnly = true)
    public DroneBorrowPageVO current(Long pilot) {
        var rows = borrows.findByPilotIdAndStatusOrderByBorrowedAtDesc(pilot, DroneBorrow.APPROVED);
        return new DroneBorrowPageVO(rows.stream().map(views::borrow).toList(), 0, false);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

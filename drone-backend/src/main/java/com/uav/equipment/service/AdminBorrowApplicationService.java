package com.uav.equipment.service;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.equipment.mapper.DroneBorrowRepository;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.equipment.pojo.dto.BorrowApproveDTO;
import com.uav.equipment.pojo.dto.BorrowRejectDTO;
import com.uav.equipment.pojo.entity.DroneBorrow;
import com.uav.equipment.pojo.entity.ProviderDrone;
import com.uav.equipment.pojo.vo.AdminBorrowApplicationVO;
import com.uav.equipment.pojo.vo.DroneBorrowVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/** REQ-SUP-GOV-007: admin approves with hand-picked or random in-stock SN; reject with reason. */
@Service
public class AdminBorrowApplicationService {
    static final int MAX_SIZE = 100;
    private final DroneBorrowRepository borrows;
    private final ProviderDroneRepository drones;
    private final AircraftModelRepository models;
    private final UserRepository users;
    private final EquipmentViews views;
    private final EquipmentNotificationService notices;

    public AdminBorrowApplicationService(DroneBorrowRepository borrows, ProviderDroneRepository drones,
            AircraftModelRepository models, UserRepository users, EquipmentViews views,
            EquipmentNotificationService notices) {
        this.borrows = borrows; this.drones = drones; this.models = models; this.users = users;
        this.views = views; this.notices = notices;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminBorrowApplicationVO> list(String status, Long pilotId, int page, int size) {
        int pageSize = clamp(size);
        if (page < 0) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        if (status != null && !status.isBlank()
                && !Set.of(DroneBorrow.PENDING, DroneBorrow.APPROVED, DroneBorrow.REJECTED, DroneBorrow.RETURNED).contains(status)) {
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        }
        String filter = status == null || status.isBlank() ? null : status;
        var rows = borrows.adminSearch(filter, pilotId, PageRequest.of(page, pageSize,
                Sort.by(Sort.Order.desc("appliedAt"), Sort.Order.desc("id"))));
        Map<Long, User> pilots = pilots(rows.stream().map(DroneBorrow::getPilotId).toList());
        Map<Long, String> modelNames = models.findAllById(rows.stream().map(DroneBorrow::getAircraftModelId).distinct().toList())
                .stream().collect(Collectors.toMap(m -> m.getId(), m -> m.getDisplayName()));
        Map<String, ProviderDrone> droneMap = drones.findAllById(rows.stream().map(DroneBorrow::getDroneId)
                .filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(ProviderDrone::getId, Function.identity()));
        Set<Long> pilotsWithLoan = rows.stream().map(DroneBorrow::getPilotId).distinct()
                .filter(pid -> borrows.countByPilotIdAndStatus(pid, DroneBorrow.APPROVED) > 0)
                .collect(Collectors.toSet());
        List<AdminBorrowApplicationVO> content = rows.stream().map(b -> {
            ProviderDrone d = b.getDroneId() == null ? null : droneMap.get(b.getDroneId());
            return new AdminBorrowApplicationVO(b.getId(), b.getStatus(), b.getPilotId(), name(pilots.get(b.getPilotId())),
                    b.getAircraftModelId(), modelNames.getOrDefault(b.getAircraftModelId(), ""),
                    b.getOrderId(), b.getAppliedAt(), b.getDroneId(), d == null ? null : d.getSerialNumber(),
                    b.getBorrowedAt(), b.getReturnedAt(), b.getRejectReason(),
                    pilotsWithLoan.contains(b.getPilotId()),
                    drones.countInStockByModel(b.getAircraftModelId()));
        }).toList();
        return AdminPageVo.of(content, page, pageSize, rows.getTotalElements());
    }

    @Transactional
    public DroneBorrowVO approve(Long adminId, String id, BorrowApproveDTO input) {
        if (input == null) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        boolean random = Boolean.TRUE.equals(input.random());
        String droneId = input.droneId() == null || input.droneId().isBlank() ? null : input.droneId().trim();
        if (random == (droneId != null)) {
            throw EquipmentAccess.conflict("通过时请手选机身号，或指定 random=true，二者择一");
        }
        var app = borrows.lockById(id).orElseThrow(EquipmentAccess::missing);
        if (!DroneBorrow.PENDING.equals(app.getStatus())) {
            throw EquipmentAccess.conflict("仅待审批申请可通过");
        }
        ProviderDrone d;
        if (random) {
            List<ProviderDrone> stock = drones.findInStockByModel(app.getAircraftModelId());
            if (stock.isEmpty()) throw EquipmentAccess.conflict("该机型当前无在库启用飞机，无法通过");
            d = drones.lockDrone(stock.get(ThreadLocalRandom.current().nextInt(stock.size())).getId())
                    .orElseThrow(EquipmentAccess::missing);
        } else {
            d = drones.lockDrone(droneId).orElseThrow(EquipmentAccess::missing);
        }
        if (!d.getAircraftModelId().equals(app.getAircraftModelId())) {
            throw EquipmentAccess.conflict("只能出借申请机型下的在库飞机");
        }
        if (!d.isEnabled() || d.getCurrentBorrowId() != null) {
            throw EquipmentAccess.conflict("设备已借出或已停用，请刷新后重选");
        }
        LocalDateTime now = LocalDateTime.now();
        app.setDroneId(d.getId());
        app.setStatus(DroneBorrow.APPROVED);
        app.setBorrowedAt(now);
        app.setReviewedBy(adminId);
        app.setReviewedAt(now);
        borrows.saveAndFlush(app);
        d.setCurrentBorrowId(app.getId());
        drones.saveAndFlush(d);
        notices.notifyPilot(app.getPilotId(), app.getId(), EquipmentNotificationService.KIND_APPROVED,
                "借机申请已通过", "已出借机身号 " + d.getSerialNumber() + "。");
        notices.notifyAdmins(app.getId(), EquipmentNotificationService.KIND_OUT,
                "平台无人机已借出", "机身号 " + d.getSerialNumber() + " 已出借给飞手。");
        return views.borrow(app);
    }

    @Transactional
    public DroneBorrowVO reject(Long adminId, String id, BorrowRejectDTO input) {
        var app = borrows.lockById(id).orElseThrow(EquipmentAccess::missing);
        if (!DroneBorrow.PENDING.equals(app.getStatus())) {
            throw EquipmentAccess.conflict("仅待审批申请可驳回");
        }
        LocalDateTime now = LocalDateTime.now();
        app.setStatus(DroneBorrow.REJECTED);
        app.setRejectReason(input.reason().trim());
        app.setReviewedBy(adminId);
        app.setReviewedAt(now);
        borrows.saveAndFlush(app);
        notices.notifyPilot(app.getPilotId(), app.getId(), EquipmentNotificationService.KIND_REJECTED,
                "借机申请已驳回", "原因：" + app.getRejectReason());
        return views.borrow(app);
    }

    /** REQ-SUP-GOV-007 / GOV-005: undo mistaken pilot return while airframe still in stock. */
    @Transactional
    public DroneBorrowVO restore(Long adminId, String id) {
        var app = borrows.lockById(id).orElseThrow(EquipmentAccess::missing);
        if (!DroneBorrow.RETURNED.equals(app.getStatus()) || app.getDroneId() == null) {
            throw EquipmentAccess.conflict("仅已归还记录可改回已借出");
        }
        var d = drones.lockDrone(app.getDroneId()).orElseThrow(EquipmentAccess::missing);
        if (d.getCurrentBorrowId() != null) {
            throw EquipmentAccess.conflict("该机当前已有其他借用，无法改回");
        }
        app.setStatus(DroneBorrow.APPROVED);
        app.setReturnedAt(null);
        app.setReviewedBy(adminId);
        app.setReviewedAt(LocalDateTime.now());
        borrows.saveAndFlush(app);
        d.setCurrentBorrowId(app.getId());
        drones.saveAndFlush(d);
        return views.borrow(app);
    }

    private Map<Long, User> pilots(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }
    private static String name(User u) { return u == null ? "" : u.getUserName(); }
    private static int clamp(int size) {
        if (size < 1) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        return Math.min(size, MAX_SIZE);
    }
}

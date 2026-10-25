package com.uav.dispatch.service;

import com.uav.dispatch.mapper.DispatchAssignmentRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchAssignment;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.*;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** REQ-PILOT-MATCH-004 / REQ-PILOT-EXEC-006: real server assignments, independent of simulated fees/data. */
@Service
public class DispatchMatchingService {
    private static final Set<String> OFFER_FIELDS = Set.of("category", "weightMin", "weightMax", "length", "width", "height",
            "count", "packaging", "lifting", "timeMode", "scheduledAt", "urgent",
            "start.lat", "start.lng", "start.site", "end.lat", "end.lng", "end.site");
    private final DispatchOrderRepository orders;
    private final DispatchAssignmentRepository assignments;
    private final DispatchNotificationService notifications;
    private final com.uav.flight.service.FlightRecordService flight;
    public DispatchMatchingService(DispatchOrderRepository orders, DispatchAssignmentRepository assignments, DispatchNotificationService notifications, com.uav.flight.service.FlightRecordService flight) {
        this.orders = orders;
        this.assignments = assignments;
        this.notifications = notifications; this.flight = flight;
    }
    @Transactional(readOnly = true)
    public DispatchHallVO hall(int page) {
        var result = orders.findByStageAndFundsStatusOrderByCreatedAtAsc("WAITING_ACCEPTANCE", "PAID", PageRequest.of(page, 20));
        return new DispatchHallVO(result.getContent().stream().map(DispatchMatchingService::offer).toList(), page, result.hasNext());
    }
    @Transactional(readOnly = true)
    public List<DispatchOrderVO> mine(Long pilot) {
        return orders.findTop50ByAssignedPilotIdOrderByUpdatedAtDesc(pilot).stream().map(DispatchOrderViews::view).toList();
    }
    @Transactional(readOnly = true)
    public DispatchOrderVO get(Long pilot, String id) {
        return orders.findById(id).filter(o -> pilot.equals(o.getAssignedPilotId())).map(DispatchOrderViews::view)
                .orElseThrow(this::notFound);
    }
    @Transactional
    public DispatchOrderVO claim(Long pilot, String id, long offerRevision) {
        DispatchOrder order = locked(id);
        if (pilot.equals(order.getUserId())) throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
        if (pilot.equals(order.getAssignedPilotId()) && List.of("PILOT_PREPARING", "PILOT_ARRIVED").contains(order.getStage())) {
            var assignment = assignments.findByOrderIdAndAssignmentVersion(id, order.getAssignmentVersion()).orElseThrow(this::stale);
            if (assignment.getOfferRevision() == offerRevision) return DispatchOrderViews.view(order);
        }
        if (!order.getStage().equals("WAITING_ACCEPTANCE") || !order.getFundsStatus().equals("PAID")
                || order.getAssignedPilotId() != null || order.getRevision() != offerRevision) throw stale();
        order.setAssignedPilotId(pilot);
        order.setAssignmentVersion(order.getAssignmentVersion() + 1);
        order.setStage("PILOT_PREPARING");
        order.setUpdatedAt(LocalDateTime.now());
        DispatchAssignment assignment = new DispatchAssignment();
        assignment.setOrderId(id);
        assignment.setPilotId(pilot);
        assignment.setAssignmentVersion(order.getAssignmentVersion());
        assignment.setOfferRevision(offerRevision);
        assignment.setAcceptedAt(order.getUpdatedAt());
        notifications.owner(order, "CLAIMED", "飞手已接单", "飞手正在准备前往起点，请保持联系人电话畅通。");
        assignments.save(assignment);
        return DispatchOrderViews.view(orders.saveAndFlush(order));
    }
    @Transactional
    public DispatchOrderVO arrive(Long pilot, String id, long version) {
        DispatchOrder order = locked(id);
        requireAssignment(order, pilot, version);
        if (order.getStage().equals("PILOT_ARRIVED")) return DispatchOrderViews.view(order);
        if (!order.getStage().equals("PILOT_PREPARING")) throw stale();
        var assignment = assignments.findByOrderIdAndAssignmentVersion(id, version).orElseThrow(this::stale);
        assignment.setArrivedAt(LocalDateTime.now());
        assignments.save(assignment);
        order.setStage("PILOT_ARRIVED");
        order.setUpdatedAt(assignment.getArrivedAt());
        notifications.owner(order, "ARRIVED", "飞手已确认到场", "请现场核对货物、吊点和场地。到场由飞手本人确认。");
        return DispatchOrderViews.view(orders.saveAndFlush(order));
    }
    @Transactional
    public void release(Long pilot, String id, long version) {
        DispatchOrder order = locked(id);
        var assignment = assignments.findByOrderIdAndAssignmentVersion(id, version).orElseThrow(this::stale);
        if (!pilot.equals(assignment.getPilotId())) throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
        // An old retry can acknowledge its own completed release, but cannot release a subsequent assignment.
        if ("PILOT_RELEASED".equals(assignment.getEndReason())) return;
        requireAssignment(order, pilot, version);
        if (!List.of("PILOT_PREPARING", "PILOT_ARRIVED").contains(order.getStage()) || assignment.getEndedAt() != null) throw stale();
        flight.closeOrder(id, "PILOT_RELEASED");
        assignment.setEndedAt(LocalDateTime.now());
        assignment.setEndReason("PILOT_RELEASED");
        assignments.save(assignment);
        notifications.owner(order, "REDISPATCHED", "飞手已取消，重新派单中", "本单已返回大厅，已付定金保持不变。");
        order.setAssignedPilotId(null);
        order.setStage("WAITING_ACCEPTANCE");
        order.setNearbyWaitingSince(LocalDateTime.now());
        order.setUpdatedAt(assignment.getEndedAt());
        orders.saveAndFlush(order);
    }
    static DispatchOfferVO offer(DispatchOrder order) {
        Map<String, String> fields = new LinkedHashMap<>();
        DispatchOrderViews.fields(order).forEach((key, value) -> { if (OFFER_FIELDS.contains(key)) fields.put(key, value); });
        return new DispatchOfferVO(order.getId(), order.getRevision(), fields, order.getPilotServiceFee(), order.getCreatedAt());
    }
    private void requireAssignment(DispatchOrder order, Long pilot, long version) {
        if (!pilot.equals(order.getAssignedPilotId()) || order.getAssignmentVersion() != version) throw stale();
    }
    private DispatchOrder locked(String id) { return orders.lockOrder(id).orElseThrow(this::notFound); }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND); }
    private BusinessException stale() {
        return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.DISPATCH_ASSIGNMENT_CHANGED);
    }
}

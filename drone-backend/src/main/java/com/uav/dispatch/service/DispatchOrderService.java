package com.uav.dispatch.service;

import com.uav.dispatch.mapper.DispatchAssignmentRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.dto.DispatchOrderDTO;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.DispatchOrderVO;
import com.uav.server.config.DispatchPaymentConfig;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.user.pojo.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** REQ-SHIP-PUB-003: owner-scoped simulated deposit lifecycle; no live matching or real money. */
@Service
public class DispatchOrderService {
    private final DispatchOrderRepository orders;
    private final WaybillAssessmentService assessments;
    private final DispatchPaymentConfig config;
    private final DispatchSandboxPayment payment;
    private final EntityManager entityManager;
    private final DispatchAssignmentRepository assignments;
    private final DispatchNotificationService notifications;
    private final com.uav.flight.service.FlightRecordService flight;
    public DispatchOrderService(DispatchOrderRepository orders, WaybillAssessmentService assessments,
            DispatchPaymentConfig config, DispatchSandboxPayment payment, EntityManager entityManager,
            DispatchAssignmentRepository assignments, DispatchNotificationService notifications, com.uav.flight.service.FlightRecordService flight) {
        this.orders = orders;
        this.assessments = assessments;
        this.config = config;
        this.payment = payment;
        this.entityManager = entityManager;
        this.assignments = assignments;
        this.notifications = notifications; this.flight = flight;
    }

    @Transactional
    public DispatchOrderVO create(Long owner, DispatchOrderDTO input) {
        payment.requireAvailable();
        if (!Boolean.TRUE.equals(input.declarationAccepted())) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        // Per-owner lock serializes initial same-key inserts as well as retries over different devices.
        if (entityManager.find(User.class, owner, LockModeType.PESSIMISTIC_WRITE) == null)
            throw new BusinessException(ApiErrorCode.USER_NOT_FOUND);
        String fingerprint = WaybillAssessmentService.fingerprint(input.waybill().fields());
        var existing = orders.findByUserIdAndRequestKey(owner, input.requestKey());
        if (existing.isPresent()) {
            if (!existing.get().getFingerprint().equals(fingerprint)) throw new BusinessException(
                    HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_CONFLICT);
            if (existing.get().getStage().equals("AWAITING_DEPOSIT")
                    && !existing.get().getAssessmentId().equals(input.assessmentId())) {
                // Refresh an expired assessment without creating another order or changing money snapshots.
                var fresh = assessments.requireValid(owner, input.assessmentId(), input.waybill());
                existing.get().setAssessmentId(fresh.getId());
                existing.get().setUpdatedAt(LocalDateTime.now());
                notifications.changed(existing.get());
                return view(orders.saveAndFlush(existing.get()));
            }
            return view(existing.get());
        }
        var assessment = assessments.requireValid(owner, input.assessmentId(), input.waybill());
        DispatchOrder order = new DispatchOrder();
        order.setId(UUID.randomUUID().toString());
        order.setUserId(owner);
        order.setRequestKey(input.requestKey());
        order.setAssessmentId(assessment.getId());
        order.setFingerprint(fingerprint);
        order.setFieldsJson(assessment.getFieldsJson());
        var quote = DispatchPricingService.decode(assessment.getQuoteJson());
        if (quote == null) throw new BusinessException(ApiErrorCode.ASSESSMENT_INVALID);
        order.setQuoteJson(assessment.getQuoteJson());
        order.setDepositAmount(quote.depositAmount());
        order.setPilotServiceFee(quote.pilotServiceFee());
        order.setStage("AWAITING_DEPOSIT");
        order.setFundsStatus("UNPAID");
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(order.getCreatedAt());
        notifications.changed(order);
        return view(orders.saveAndFlush(order));
    }

    @Transactional(readOnly = true)
    public DispatchOrderVO get(Long owner, String id) {
        return orders.findById(id).filter(o -> o.getUserId().equals(owner)).map(this::view)
                .orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public List<DispatchOrderVO> list(Long owner) {
        return orders.findTop50ByUserIdOrderByCreatedAtDesc(owner).stream().map(this::view).toList();
    }

    @Transactional
    public DispatchOrderVO simulatePay(Long owner, String id) {
        payment.requireAvailable();
        DispatchOrder order = orders.lockOwned(id, owner).orElseThrow(this::notFound);
        if (!order.getStage().equals("CANCELLED") && order.getPaymentReference() != null) return view(order);
        if (!order.getStage().equals("AWAITING_DEPOSIT") || !order.getFundsStatus().equals("UNPAID")) throw invalidState();
        // Recheck time-dependent input/airspace snapshot before the simulated charge.
        Map<String, String> fields = fields(order);
        assessments.requireValid(owner, order.getAssessmentId(), new com.uav.dispatch.pojo.dto.WaybillDraftDTO(1, 4, fields, null));
        order.setPaymentReference("sim-pay-" + order.getId());
        order.setFundsStatus("PAID");
        order.setStage("WAITING_ACCEPTANCE");
        order.setNearbyWaitingSince(LocalDateTime.now());
        notifications.owner(order, "DEPOSIT_PAID", "定金模拟支付成功", "订单已进入大厅等待接单，无真实资金变动。");
        order.setUpdatedAt(LocalDateTime.now());
        return view(orders.saveAndFlush(order));
    }

    @Transactional
    public DispatchOrderVO cancel(Long owner, String id) {
        payment.requireAvailable();
        DispatchOrder order = orders.lockOwned(id, owner).orElseThrow(this::notFound);
        if (order.getStage().equals("CANCELLED")) return view(order);
        if (!List.of("AWAITING_DEPOSIT", "WAITING_ACCEPTANCE", "PILOT_PREPARING", "PILOT_ARRIVED", "IN_TRANSIT", "DELIVERY_CONFIRMATION")
                .contains(order.getStage())) throw invalidState();
        if (order.getAssignedPilotId() != null) {
            var assignment = assignments.findByOrderIdAndAssignmentVersion(id, order.getAssignmentVersion()).orElseThrow(this::invalidState);
            assignment.setEndedAt(LocalDateTime.now());
            assignment.setEndReason("SHIPPER_CANCELLED");
            assignments.save(assignment);
        }
        if (order.getFundsStatus().equals("PAID")) {
            // The simulation completes atomically. This is never a real-channel refund acknowledgement.
            order.setRefundReference("sim-refund-" + order.getId());
            order.setFundsStatus("REFUNDED");
        }
        flight.closeOrder(id, "CANCELLED");
        order.setStage("CANCELLED");
        notifications.owner(order, "CANCELLED", order.getFundsStatus().equals("REFUNDED") ? "运单已取消，定金已模拟退还" : "运单已取消",
                order.getFundsStatus().equals("REFUNDED") ? "定金已全额模拟退回，无真实资金变动。" : "本单未付款，无需退款。");
        notifications.pilot(order, "CANCELLED", "需求方已取消运单", "请停止执行本单。业务取消不会自动停止实际飞行，请联系现场人员处置。");
        order.setUpdatedAt(LocalDateTime.now());
        return view(orders.saveAndFlush(order));
    }

    private DispatchOrderVO view(DispatchOrder order) {
        return DispatchOrderViews.view(order);
    }
    private Map<String, String> fields(DispatchOrder order) {
        return DispatchOrderViews.fields(order);
    }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND); }
    private BusinessException invalidState() { return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.ORDER_STATUS_INVALID); }
}

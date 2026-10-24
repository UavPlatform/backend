package com.uav.dispatch.service;

import com.alibaba.fastjson.JSON;
import com.uav.dispatch.mapper.DispatchAssignmentRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.DispatchOrderVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class DispatchFulfillmentService {
    private final DispatchOrderRepository orders;
    private final DispatchAssignmentRepository assignments;
    private final DispatchMaterialService materials;
    private final DispatchSandboxPayment payment;
    private final DispatchNotificationService notifications;
    private final com.uav.flight.service.FlightRecordService flight;
    public DispatchFulfillmentService(DispatchOrderRepository orders, DispatchAssignmentRepository assignments,
            DispatchMaterialService materials, DispatchSandboxPayment payment, DispatchNotificationService notifications, com.uav.flight.service.FlightRecordService flight) {
        this.orders = orders; this.assignments = assignments; this.materials = materials; this.payment = payment; this.notifications = notifications; this.flight = flight;
    }
    @Transactional
    public DispatchOrderVO start(Long pilot, String id, long version) {
        var order = pilotOrder(pilot, id, version);
        if (order.getStage().equals("IN_TRANSIT")) return DispatchOrderViews.view(order);
        if (!order.getStage().equals("PILOT_ARRIVED") || order.getQuoteJson() == null) throw state();
        notifications.owner(order, "TRANSPORT_STARTED", "飞手已确认开始运输", "运输业务状态已更新；实际飞行由飞手操作，此通知不代表系统核验已起飞。");
        order.setStartedAt(LocalDateTime.now()); order.setUpdatedAt(order.getStartedAt()); order.setStage("IN_TRANSIT");
        return DispatchOrderViews.view(orders.saveAndFlush(order));
    }
    @Transactional
    public DispatchOrderVO deliver(Long pilot, String id, long version, List<String> ids) {
        var order = pilotOrder(pilot, id, version);
        String canonical = canonical(ids);
        if (order.getStage().equals("DELIVERY_CONFIRMATION") && canonical.equals(order.getPilotMaterialIdsJson())) return DispatchOrderViews.view(order);
        if (!order.getStage().equals("IN_TRANSIT")) throw state();
        materials.requireVerified(order, pilot, "PILOT_DELIVERY", ids);
        notifications.owner(order, "DELIVERED", "飞手已提交送达凭证，请确认收货", "请查看送达凭证，并上传本次收货材料后确认收货。");
        flight.closeOrder(id, "DELIVERED");
        order.setPilotMaterialIdsJson(canonical); order.setDeliveredAt(LocalDateTime.now());
        order.setUpdatedAt(order.getDeliveredAt()); order.setStage("DELIVERY_CONFIRMATION");
        return DispatchOrderViews.view(orders.saveAndFlush(order));
    }
    @Transactional
    public DispatchOrderVO receipt(Long owner, String id, List<String> ids) {
        var order = ownerOrder(owner, id);
        String canonical = canonical(ids);
        if (order.getStage().equals("COMPLETED") && canonical.equals(order.getReceiptMaterialIdsJson())) return DispatchOrderViews.view(order);
        if (!order.getStage().equals("DELIVERY_CONFIRMATION") || order.getQuoteJson() == null) throw state();
        materials.requireVerified(order, owner, "SHIPPER_RECEIPT", ids);
        materials.requireVerified(order, order.getAssignedPilotId(), "PILOT_DELIVERY", JSON.parseArray(order.getPilotMaterialIdsJson(), String.class));
        var quote = DispatchPricingService.decode(order.getQuoteJson());
        order.setBalanceAmount(quote.totalAmount().subtract(order.getDepositAmount()));
        if (order.getBalanceAmount().signum() < 0) payment.requireAvailable();
        order.setReceiptMaterialIdsJson(canonical); order.setReceiptAt(LocalDateTime.now());
        order.setCompletedAt(order.getReceiptAt()); order.setUpdatedAt(order.getReceiptAt()); order.setStage("COMPLETED");
        if (order.getBalanceAmount().signum() > 0) order.setFundsStatus("BALANCE_DUE");
        else {
            order.setFundsStatus("SETTLED"); order.setSettledAt(order.getCompletedAt());
            if (order.getBalanceAmount().signum() < 0) {
                order.setRefundReference("sim-difference-" + id); order.setSettlementReference("sim-difference-" + id);
            }
        }
        String outcome = order.getBalanceAmount().signum() > 0 ? "抵扣定金后待模拟支付尾款：¥" + order.getBalanceAmount().toPlainString()
                : order.getBalanceAmount().signum() < 0 ? "多付定金已模拟退差额：¥" + order.getBalanceAmount().negate().toPlainString() : "定金已抵扣全部费用，无需支付尾款。";
        notifications.owner(order, "COMPLETED", "运单已结单", outcome);
        notifications.pilot(order, "COMPLETED", "需求方已确认收货，运单已结单", "双方交付材料已核验并完成结单。");
        var assignment = assignments.findByOrderIdAndAssignmentVersion(id, order.getAssignmentVersion()).orElseThrow(this::state);
        assignment.setEndedAt(order.getCompletedAt()); assignment.setEndReason("COMPLETED"); assignments.save(assignment);
        return DispatchOrderViews.view(orders.saveAndFlush(order));
    }
    @Transactional
    public DispatchOrderVO settle(Long owner, String id) {
        payment.requireAvailable();
        var order = ownerOrder(owner, id);
        if (!order.getStage().equals("COMPLETED")) throw state();
        if (order.getFundsStatus().equals("SETTLED")) return DispatchOrderViews.view(order);
        if (!order.getFundsStatus().equals("BALANCE_DUE") || order.getBalanceAmount() == null || order.getBalanceAmount().signum() <= 0) throw state();
        notifications.owner(order, "SETTLED", "尾款已模拟结算", "尾款结算完成，可以评价本单。无真实资金变动。");
        order.setSettlementReference("sim-balance-" + id); order.setFundsStatus("SETTLED");
        order.setSettledAt(LocalDateTime.now()); order.setUpdatedAt(order.getSettledAt());
        return DispatchOrderViews.view(orders.saveAndFlush(order));
    }
    @Transactional
    public DispatchOrderVO review(Long owner, String id, int score, String comment) {
        var order = ownerOrder(owner, id);
        if (!order.getStage().equals("COMPLETED") || !order.getFundsStatus().equals("SETTLED")) throw state();
        String text = comment == null ? "" : comment.trim();
        if (score < 1 || score > 5 || text.length() > 500) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        if (order.getReviewScore() != null) {
            if (order.getReviewScore() == score && Objects.equals(order.getReviewComment(), text)) return DispatchOrderViews.view(order);
            throw state();
        }
        order.setReviewScore(score); order.setReviewComment(text); order.setUpdatedAt(LocalDateTime.now());
        notifications.changed(order);
        return DispatchOrderViews.view(orders.saveAndFlush(order));
    }
    private DispatchOrder ownerOrder(Long owner, String id) { return orders.lockOwned(id, owner).orElseThrow(this::notFound); }
    private DispatchOrder pilotOrder(Long pilot, String id, long version) {
        var order = orders.lockOrder(id).orElseThrow(this::notFound);
        if (!pilot.equals(order.getAssignedPilotId()) || version != order.getAssignmentVersion()) throw state();
        return order;
    }
    private String canonical(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(Objects::isNull)) throw new BusinessException(ApiErrorCode.DELIVERY_EVIDENCE_REQUIRED);
        return JSON.toJSONString(ids.stream().sorted().toList());
    }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND); }
    private BusinessException state() { return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.ORDER_STATUS_INVALID); }
}

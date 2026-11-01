package com.uav.dispatch.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.DispatchOrderVO;
import com.uav.dispatch.pojo.vo.DispatchBillVO;
import com.uav.dispatch.pojo.vo.DispatchExecutedDroneVO;
import java.util.Map;

final class DispatchOrderViews {
    private DispatchOrderViews() { }
    static Map<String, String> fields(DispatchOrder order) {
        return JSON.parseObject(order.getFieldsJson(), new TypeReference<Map<String, String>>() { });
    }
    static DispatchOrderVO view(DispatchOrder order) {
        return new DispatchOrderVO(order.getId(), order.getStage(), order.getFundsStatus(), "SIMULATED",
                order.getDepositAmount(), order.getPilotServiceFee(), "CNY", order.getPaymentReference(),
                order.getRefundReference(), fields(order), order.getCreatedAt(), order.getUpdatedAt(),
                order.getAssignedPilotId(), order.getAssignmentVersion(),
                order.getQuoteJson() == null ? null : new DispatchBillVO(DispatchPricingService.decode(order.getQuoteJson()),
                        order.getCompletedAt() == null ? null : DispatchPricingService.decode(order.getQuoteJson()).totalAmount(),
                        order.getBalanceAmount(), order.getSettlementReference(), order.getSettledAt(),
                        order.getReviewScore(), order.getReviewComment()),
                order.getStartedAt(), order.getDeliveredAt(), order.getReceiptAt(),
                order.getExecutedSerialNumber() == null ? null : new DispatchExecutedDroneVO(order.getExecutedSerialNumber(),
                        order.getExecutedModelCode(), order.getExecutedModelName(), order.getExecutedAircraftModelId()),
                order.getDepositPaidAt(), order.getWaitingSince(), order.getAcceptedAt(), order.getArrivedAt(),
                order.getDeliveryNote());
    }
}

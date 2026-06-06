package com.uav.order.pojo.vo;

import com.uav.order.pojo.entity.MissionOrder;

import java.math.BigDecimal;

public record OrderVO(
        String orderNum,
        BigDecimal totalAmount,
        BigDecimal distance,
        String taskName,
        int orderStatus
) {
    public static OrderVO from(MissionOrder order) {
        return new OrderVO(
                order.getOrderNum(),
                order.getTotalAmount(),
                order.getTotalDistance(),
                order.getTask() != null ? order.getTask().getTaskName() : null,
                order.getOrderStatus().getCode()
        );
    }
}

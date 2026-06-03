package com.drone.pojo.vo.order;

import com.drone.pojo.entity.MissionOrder;

import java.math.BigDecimal;

public record OrderVO(
        String orderNum,
        BigDecimal totalAmount,
        BigDecimal distance,
        String djiId,
        String routeName,
        int orderStatus
) {
    public static OrderVO from(MissionOrder order) {
        return new OrderVO(
                order.getOrderNum(),
                order.getTotalAmount(),
                order.getTotalDistance(),
                order.getDjiId(),
                order.getRoute() != null ? order.getRoute().getRouteName() : null,
                order.getOrderStatus().getCode()
        );
    }
}

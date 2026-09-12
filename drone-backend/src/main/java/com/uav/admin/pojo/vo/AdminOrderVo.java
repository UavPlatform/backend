package com.uav.admin.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.order.pojo.entity.MissionOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 1B-5 管理端订单契约（C5：状态映射补全 4/5）。
 * orderStatusCode 为后端枚举数值（0-5），orderStatus 为枚举名，orderStatusDesc 为中文描述；
 * 前端按 code 渲染状态标签（0 待支付 / 1 已支付 / 2 已取消 / 3 已退款 / 4 已完成 / 5 待验收）。
 */
public record AdminOrderVo(
        String orderNum,
        Long userId,
        String ownerName,
        String taskNum,
        String taskName,
        BigDecimal totalAmount,
        BigDecimal totalDistance,
        int orderStatusCode,
        String orderStatus,
        String orderStatusDesc,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createTime,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updateTime) {

    public static AdminOrderVo of(MissionOrder order, String ownerName, String taskName) {
        return new AdminOrderVo(
                order.getOrderNum(),
                order.getUserId(),
                ownerName,
                order.getTask() != null ? order.getTask().getTaskNum() : null,
                taskName,
                order.getTotalAmount(),
                order.getTotalDistance(),
                order.getOrderStatus().getCode(),
                order.getOrderStatus().name(),
                order.getOrderStatus().getDesc(),
                order.getCreateTime(),
                order.getUpdateTime());
    }
}

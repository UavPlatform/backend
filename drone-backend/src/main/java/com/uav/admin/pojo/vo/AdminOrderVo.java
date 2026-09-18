package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.order.pojo.entity.MissionOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 1B-5 管理端订单契约（C5：状态映射补全 4/5）。
 * orderStatusCode 为后端枚举数值（0-5），orderStatus 为枚举名，orderStatusDesc 为中文描述；
 * 前端按 code 渲染状态标签（0 待支付 / 1 已支付 / 2 已取消 / 3 已退款 / 4 已完成 / 5 待验收）。
 */
@Schema(description = "管理端订单信息")
public record AdminOrderVo(
        @Schema(description = "订单号")
        String orderNum,
        @Schema(description = "下单用户ID")
        Long userId,
        @Schema(description = "下单用户名称")
        String ownerName,
        @Schema(description = "关联任务编号")
        String taskNum,
        @Schema(description = "关联任务名称")
        String taskName,
        @Schema(description = "订单总金额，单位：元")
        BigDecimal totalAmount,
        @Schema(description = "订单总里程，单位：米")
        BigDecimal totalDistance,
        @Schema(description = "订单状态码（0 待支付 / 1 已支付 / 2 已取消 / 3 已退款 / 4 已完成 / 5 待确认完成 / 6 争议中）")
        int orderStatusCode,
        @Schema(description = "订单状态枚举名（PENDING/PAID/CANCELLED/REFUNDED/COMPLETED/WAITING_CONFIRM/DISPUTED）")
        String orderStatus,
        @Schema(description = "订单状态中文描述")
        String orderStatusDesc,
        @Schema(description = "创建时间，格式 yyyy-MM-dd HH:mm:ss")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createTime,
        @Schema(description = "更新时间，格式 yyyy-MM-dd HH:mm:ss")
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

package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.task.pojo.entity.Task;

import java.time.LocalDateTime;

/**
 * 1B-5 管理端任务契约：双状态（taskStatus × orderStatus）+ actionHint 操作提示，
 * 供运营端渲染 状态×操作 矩阵（全景 P1-8）。
 */
@Schema(description = "管理端任务信息（含任务状态与关联订单状态）")
public record AdminTaskVo(
        @Schema(description = "任务ID")
        Long id,
        @Schema(description = "任务编号")
        String taskNum,
        @Schema(description = "任务名称")
        String taskName,
        @Schema(description = "发布用户ID")
        Long userId,
        @Schema(description = "发布用户名称")
        String ownerName,
        @Schema(description = "任务状态（IDLE 空闲中 / IN_PROGRESS 执行中 / COMPLETED 执行完毕）")
        String taskStatus,
        @Schema(description = "任务状态中文描述")
        String taskStatusDesc,
        @Schema(description = "任务期望执行时间，格式 yyyy-MM-dd HH:mm:ss")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime taskTime,
        @Schema(description = "任务奖励金额，单位：元（服务端按航点距离计价，客户端传入的 reward 仅作参考）")
        Double reward,
        @Schema(description = "任务描述")
        String description,
        @Schema(description = "关联订单号（无关联订单时为 null）")
        String orderNum,
        @Schema(description = "关联订单状态码（0 待支付 / 1 已支付 / 2 已取消 / 3 已退款 / 4 已完成 / 5 待确认完成 / 6 争议中；无关联订单时为 null）")
        Integer orderStatusCode,
        @Schema(description = "关联订单状态枚举名（PENDING/PAID/CANCELLED/REFUNDED/COMPLETED/WAITING_CONFIRM/DISPUTED；无关联订单时为 null）")
        String orderStatus,
        @Schema(description = "关联订单状态中文描述（无关联订单时为 null）")
        String orderStatusDesc,
        @Schema(description = "关联订单总金额，单位：元（无关联订单时为 null）")
        java.math.BigDecimal totalAmount,
        @Schema(description = "关联订单总里程，单位：米（无关联订单时为 null）")
        java.math.BigDecimal totalDistance,
        @Schema(description = "接单飞手名称（未接单时为 null）")
        String riderName,
        @Schema(description = "飞手完成说明（未完成或飞手未填写时为 null）")
        String completeNote,
        @Schema(description = "操作提示文案（由任务状态 × 订单状态计算，客户端可直接渲染）")
        String actionHint,
        @Schema(description = "创建时间，格式 yyyy-MM-dd HH:mm:ss")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createTime,
        @Schema(description = "更新时间，格式 yyyy-MM-dd HH:mm:ss")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updateTime) {

    public static AdminTaskVo of(Task task, String ownerName, String orderNum,
                                 Integer orderStatusCode, String orderStatus, String orderStatusDesc,
                                 java.math.BigDecimal totalAmount, java.math.BigDecimal totalDistance,
                                 String riderName, String completeNote, String actionHint) {
        return new AdminTaskVo(
                task.getId(),
                task.getTaskNum(),
                task.getTaskName(),
                task.getUserId(),
                ownerName,
                task.getTaskStatus().name(),
                task.getTaskStatus().getDescription(),
                task.getTaskTime(),
                task.getReward(),
                task.getDescription(),
                orderNum,
                orderStatusCode,
                orderStatus,
                orderStatusDesc,
                totalAmount,
                totalDistance,
                riderName,
                completeNote,
                actionHint,
                task.getCreateTime(),
                task.getUpdateTime());
    }
}

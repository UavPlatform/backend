package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 监管端用户详情中的争议（投诉）摘要，数据来自 {@code order_complaint}。
 */
@Schema(description = "管理端用户名下投诉摘要")
public record AdminUserComplaintVo(
        @Schema(description = "投诉ID")
        Long id,
        @Schema(description = "关联订单号")
        String orderNum,
        @Schema(description = "投诉原因枚举名（ComplaintReason）")
        String reason,
        @Schema(description = "处理状态枚举名（ComplaintStatus）")
        String status,
        @Schema(description = "用户描述")
        String description,
        @Schema(description = "提交时间")
        LocalDateTime createTime,
        @Schema(description = "处理完成时间")
        LocalDateTime resolveTime,
        @Schema(description = "管理员备注")
        String adminNote) {
}

package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * TASK-BACKEND-006 监管端注册用户列表契约（REQ-FRONTEND-001 用户实体 / ADR-0004）。
 * 列表只含 role=0 普通用户（飞手见 {@link AdminPilotVo}）；orderCount 为该用户名下订单总数。
 */
@Schema(description = "管理端注册用户（role=0）")
public record AdminUserVo(
        @Schema(description = "用户ID")
        Long userId,
        @Schema(description = "用户名（昵称）")
        String userName,
        @Schema(description = "账号状态（1 正常 / 0 停用）")
        Integer status,
        @Schema(description = "名下订单总数")
        long orderCount) {
}

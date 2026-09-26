package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * TASK-BACKEND-006 监管端用户详情契约：基本信息 + 关联订单摘要
 * （orderNum、任务、状态、金额，复用 {@link AdminOrderVo} 与订单列表同构）。
 */
@Schema(description = "管理端用户详情（含关联订单摘要）")
public record AdminUserDetailVo(
        @Schema(description = "用户ID")
        Long userId,
        @Schema(description = "用户名（昵称）")
        String userName,
        @Schema(description = "账号状态（1 正常 / 0 停用）")
        Integer status,
        @Schema(description = "角色（0 普通用户 / 2 管理员；飞手不走本详情）")
        Integer role,
        @Schema(description = "关联订单（按创建时间倒序，无订单为空数组）")
        List<AdminOrderVo> orders) {
}

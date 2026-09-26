package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * TASK-BACKEND-006 监管端注册飞手列表契约（REQ-FRONTEND-001 飞手实体 / ADR-0004）。
 * 列表只含 role=1 飞手；在线口径为「名下至少一台绑定设备 online_status=1」（onlineUavCount &gt; 0）。
 */
@Schema(description = "管理端注册飞手（role=1）")
public record AdminPilotVo(
        @Schema(description = "飞手ID（user.id）")
        Long userId,
        @Schema(description = "用户名（昵称）")
        String userName,
        @Schema(description = "账号状态（1 正常 / 0 停用）")
        Integer status,
        @Schema(description = "绑定无人机数（rider_uav 记录数）")
        int uavCount,
        @Schema(description = "在线无人机数（online_status=1 的绑定设备）")
        int onlineUavCount,
        @Schema(description = "累计完成单数（接单记录 complete_time 非空）")
        long completedCount) {
}

package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 监管端注册飞手列表（REQ-SUP-GOV-004）：不再聚合自有机绑定。 */
@Schema(description = "管理端注册飞手（role=1）")
public record AdminPilotVo(
        @Schema(description = "飞手ID（user.id）")
        Long userId,
        @Schema(description = "用户名（昵称）")
        String userName,
        @Schema(description = "账号状态（1 正常 / 0 停用）")
        Integer status,
        @Schema(description = "累计完成单数（接单记录 complete_time 非空）")
        long completedCount) {
}

package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * TASK-BACKEND-006 飞手详情的绑定无人机行契约（REQ-FRONTEND-001「归属飞手 1:N，在飞手详情维护」）。
 *
 * <p>数据来源三方：绑定关系 {@code rider_uav}（djiId/机型映射）、机型目录 {@code aircraft_model}
 * （displayName）、设备档案 {@code uav}（在线/可用）。
 * <ul>
 *   <li>{@code modelName} 为 {@code null} 表示绑定时未映射机型（注册旧路径存量数据）；</li>
 *   <li>{@code available} 为 {@code null} 表示设备尚未注册（无 {@code uav} 行）：在线视为 false，
 *       且无法执行启用/禁用；</li>
 *   <li>启用/禁用复用既有 {@code POST /admin/uav/available}（按 djiId 启停）。</li>
 * </ul>
 */
@Schema(description = "飞手绑定无人机（djiId + 机型 + 在线 + 可用）")
public record AdminPilotDroneVo(
        @Schema(description = "DJI 设备ID")
        String djiId,
        @Schema(description = "机型ID（未映射时为 null）")
        Long aircraftModelId,
        @Schema(description = "机型显示名（未映射时为 null）")
        String modelName,
        @Schema(description = "是否在线（设备档案 online_status=1；无设备档案为 false）")
        boolean online,
        @Schema(description = "是否可用/启用（设备档案 is_available=1；设备未注册时为 null，不可启停）")
        Boolean available) {
}

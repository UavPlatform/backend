package com.uav.admin.pojo.vo;

import com.uav.rider.pojo.vo.RiderQualificationVO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * TASK-BACKEND-006 监管端飞手详情契约：基本信息 + 绑定无人机表 + 关联订单。
 *
 * <p>关联订单口径：飞手已有接单记录（{@code task_assignment}）或已被用户选定
 * （{@code mission_order.selected_application_id}）的订单，按创建时间倒序。
 *
 * <p>证照与资质（飞手资料 {@code rider} / {@code rider_qualification}）：身份证号只回显脱敏值，
 * 飞手从未保存资料时证件字段为 null、资质为空数组。
 */
@Schema(description = "管理端飞手详情（含绑定无人机与关联订单）")
public record AdminPilotDetailVo(
        @Schema(description = "飞手ID（user.id）")
        Long userId,
        @Schema(description = "用户名（昵称）")
        String userName,
        @Schema(description = "账号状态（1 正常 / 0 停用）")
        Integer status,
        @Schema(description = "角色（恒为 1 飞手）")
        Integer role,
        @Schema(description = "累计完成单数（接单记录 complete_time 非空）")
        long completedCount,
        @Schema(description = "脱敏身份证号（前 3 后 4）；未登记为 null")
        String idNumberMasked,
        @Schema(description = "证件有效期截止日；未登记为 null")
        LocalDate idExpiryDate,
        @Schema(description = "证件是否已过期（有效期早于今天）；未登记有效期为 false")
        boolean idExpired,
        @Schema(description = "驾驶资质（机型 × 执照等级；无资质为空数组）")
        List<RiderQualificationVO> qualifications,
        @Schema(description = "绑定无人机表（无绑定为空数组）")
        List<AdminPilotDroneVo> drones,
        @Schema(description = "关联订单（按创建时间倒序，无订单为空数组）")
        List<AdminOrderVo> orders) {
}

package com.uav.equipment.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 管理端平台租借无人机行（ADR-0011 / ADR-0012 / REQ-SUP-GOV-005）。 */
@Schema(description = "管理端平台租借无人机")
public record AdminDroneVO(
        @Schema(description = "无人机登记 ID") String droneId,
        @Schema(description = "机型（引用 aircraft_model）") Model model,
        @Schema(description = "机身号（全局唯一，大写）") String serialNumber,
        @Schema(description = "所属机场") Airport airport,
        @Schema(description = "是否启用（停用后飞手不可借）") boolean enabled,
        @Schema(description = "借用状态：IN_STOCK 在库，BORROWED 已借出", allowableValues = {"IN_STOCK", "BORROWED"}) String borrowStatus,
        @Schema(description = "当前借用（仅 BORROWED 时有值）", nullable = true) CurrentBorrow currentBorrow,
        @Schema(description = "执飞中：有进行中（PILOT_ARRIVED / IN_TRANSIT）的派单订单 executed_serial_number 等于本机机身号") boolean executing,
        @Schema(description = "最近一笔使用本机的派单订单（按 executed_serial_number 匹配）；从未执飞为 null", nullable = true) LatestOrder latestOrder,
        @Schema(description = "登记时间") LocalDateTime createdAt) {

    @Schema(description = "机型")
    public record Model(@Schema(description = "机型 ID") Long aircraftModelId,
                        @Schema(description = "机型编码") String modelCode,
                        @Schema(description = "机型名称") String displayName) { }

    @Schema(description = "所属机场（坐标在机场实体上，本版登记不暴露）")
    public record Airport(@Schema(description = "机场 ID") Long airportId,
                          @Schema(description = "机场名称") String name) { }

    @Schema(description = "当前借用")
    public record CurrentBorrow(@Schema(description = "借用记录 ID") String borrowId,
                                @Schema(description = "借用飞手 ID") Long pilotId,
                                @Schema(description = "借用飞手用户名") String pilotName,
                                @Schema(description = "借出时间") LocalDateTime borrowedAt) { }

    @Schema(description = "最近使用订单（跳管理端派单订单详情 GET /admin/dispatch/orders/{orderId}）")
    public record LatestOrder(@Schema(description = "派单订单 ID") String orderId,
                              @Schema(description = "订单阶段") String stage,
                              @Schema(description = "下单时间") LocalDateTime createdAt) { }
}

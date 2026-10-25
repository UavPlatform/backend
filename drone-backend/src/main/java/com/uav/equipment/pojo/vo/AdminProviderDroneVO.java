package com.uav.equipment.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 管理端只读查看服务商登记的无人机与机场点位（ADR-0008，监管侧）。 */
@Schema(description = "管理端服务商无人机/机场行")
public record AdminProviderDroneVO(
        @Schema(description = "设备登记 ID") String droneId,
        @Schema(description = "服务商") Provider provider,
        @Schema(description = "机型") Model model,
        @Schema(description = "机身号") String serialNumber,
        @Schema(description = "机场名称") String airportName,
        @Schema(description = "机场纬度") BigDecimal latitude,
        @Schema(description = "机场经度") BigDecimal longitude,
        @Schema(description = "设备是否启用") boolean enabled,
        @Schema(description = "是否借出中") boolean borrowed,
        @Schema(description = "登记时间") LocalDateTime createdAt) {

    @Schema(description = "服务商主体")
    public record Provider(@Schema(description = "服务商 ID") Long providerId,
                           @Schema(description = "服务商名称") String displayName,
                           @Schema(description = "服务商账号是否启用") boolean enabled) { }

    @Schema(description = "机型")
    public record Model(@Schema(description = "机型 ID") Long aircraftModelId,
                        @Schema(description = "机型编码") String modelCode,
                        @Schema(description = "机型名称") String displayName) { }
}

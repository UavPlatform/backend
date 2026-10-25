package com.uav.aircraft.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** REQ-SUP-GOV-006: 管理员新增/编辑机型的请求体（本版四项；coefficient 仍走种子/存量，第 4 步清理前不暴露改口）。 */
@Schema(description = "管理端机型写入")
public record AdminAircraftModelDTO(
        @Schema(description = "型号代码，须与 djifly 连机上报一致（如 FC30）", example = "FC30")
        @NotBlank @Size(max = 64) String modelCode,
        @Schema(description = "显示名", example = "DJI FlyCart 30")
        @NotBlank @Size(max = 128) String displayName,
        @Schema(description = "最大载重（kg）", example = "30.00")
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 6, fraction = 2) BigDecimal maxPayloadKg,
        @Schema(description = "是否支持吊运")
        @NotNull Boolean transportEnabled) {
}

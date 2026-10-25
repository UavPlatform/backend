package com.uav.aircraft.pojo.vo;

import com.uav.aircraft.pojo.entity.AircraftModel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** REQ-SUP-GOV-006: 管理端机型列表/详情项（含停用；coefficient 只读展示）。 */
@Schema(description = "管理端机型")
public record AdminAircraftModelVO(
        @Schema(description = "机型 ID") Long id,
        @Schema(description = "型号代码") String modelCode,
        @Schema(description = "显示名") String displayName,
        @Schema(description = "最大载重（kg）") BigDecimal maxPayloadKg,
        @Schema(description = "机型系数（报价乘子；本版只读，第 4 步清理）") BigDecimal coefficient,
        @Schema(description = "是否支持吊运") Boolean transportEnabled,
        @Schema(description = "是否启用") Boolean enabled) {

    public static AdminAircraftModelVO from(AircraftModel m) {
        return new AdminAircraftModelVO(m.getId(), m.getModelCode(), m.getDisplayName(),
                m.getMaxPayloadKg(), m.getCoefficient(), m.getTransportEnabled(), m.getEnabled());
    }
}

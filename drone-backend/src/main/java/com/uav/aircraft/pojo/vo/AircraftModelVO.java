package com.uav.aircraft.pojo.vo;

import com.uav.aircraft.pojo.entity.AircraftModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 机型目录项（{@code GET /api/aircraft-models}）。
 *
 * <p>接口只返回启用机型，故 VO 不暴露 {@code enabled}；{@code transportEnabled} 供客户端
 * 在绑机/应征选择时提前区分可吊运机型，最终门禁仍以服务端校验为准。
 */
@Data
@Schema(description = "平台机型目录项")
public class AircraftModelVO {

    @Schema(description = "机型ID：绑机与吊运应征提交的 aircraftModelId")
    private Long id;

    @Schema(description = "型号编码（如 FC30、M350RTK）")
    private String modelCode;

    @Schema(description = "显示名（如 DJI FlyCart 30）")
    private String displayName;

    @Schema(description = "最大载重（kg）")
    private BigDecimal maxPayloadKg;

    @Schema(description = "机型系数：ADR-0003 报价公式乘子")
    private BigDecimal coefficient;

    @Schema(description = "是否可承接吊运")
    private Boolean transportEnabled;

    public static AircraftModelVO from(AircraftModel model) {
        AircraftModelVO vo = new AircraftModelVO();
        vo.setId(model.getId());
        vo.setModelCode(model.getModelCode());
        vo.setDisplayName(model.getDisplayName());
        vo.setMaxPayloadKg(model.getMaxPayloadKg());
        vo.setCoefficient(model.getCoefficient());
        vo.setTransportEnabled(model.getTransportEnabled());
        return vo;
    }
}

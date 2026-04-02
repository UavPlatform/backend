package com.drone.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class UavVo {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "uavName")
    private String uavName;

    @Schema(description = "设备ID(DJI ID)")
    private String djiId;

    @Schema(description = "控制器型号")
    private String controllerModel;

    public UavVo(Long id, String uavName, String djiId, String controllerModel) {
        this.id = id;
        this.uavName = uavName;
        this.djiId = djiId;
        this.controllerModel = controllerModel;
    }

    public UavVo() {
    }
}

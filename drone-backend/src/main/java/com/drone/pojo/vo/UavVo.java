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

    @Schema(description = "是否可用(1可用,0禁用)")
    private Character isAvailable;

    public UavVo(Long id, String uavName, String djiId, String controllerModel, Character isAvailable) {
        this.id = id;
        this.uavName = uavName;
        this.djiId = djiId;
        this.controllerModel = controllerModel;
        this.isAvailable = isAvailable;
    }

    public UavVo(Long id, String uavName, String djiId, String controllerModel) {
        this.id = id;
        this.uavName = uavName;
        this.djiId = djiId;
        this.controllerModel = controllerModel;
    }

    public UavVo() {
    }
}

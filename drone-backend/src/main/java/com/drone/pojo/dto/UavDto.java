package com.drone.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.swing.*;

@Data
@Schema(description = "Parameters for Add Request")
public class UavDto {
    @Schema(description = "uav_name")
    private String uavName;

    @Schema(description = "online_status")
    private Character onlineStatus; // 0离线, 1在线

    @Schema(description = "dji_id")
    private String djiId;

    @Schema(description = "controller_model")
    private String controllerModel;


    public boolean hasEmptyField(){
        return this.getDjiId() == null || this.getUavName() == null || this.getOnlineStatus() == null || this.getControllerModel() == null;
    }
}

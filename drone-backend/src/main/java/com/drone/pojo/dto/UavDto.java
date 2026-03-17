package com.drone.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Parameters for Add Request(新增参数)")
public class UavDto {
    @Schema(description = "uav_name")
    private String uavName;

    @Schema(description = "online_status")
    private Character onlineStatus; // 0离线, 1在线
}

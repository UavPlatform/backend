package com.drone.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class UavVo {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "uavName")
    private String uavName;

    public UavVo(Long id, String uavName) {
        this.id = id;
        this.uavName = uavName;
    }

    public UavVo() {
    }
}

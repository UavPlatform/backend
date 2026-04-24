package com.drone.pojo.dto;

import lombok.Data;

// 路径点DTO
@Data
public class WaypointDto {
    private Integer orderIndex;
    private Double longitude;
    private Double latitude;
    private Double altitude;
    private Integer stayTime;
}

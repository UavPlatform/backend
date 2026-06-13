package com.uav.task.pojo.dto;

import lombok.Data;

@Data
public class WaypointDto {
    private Integer orderIndex;
    private Double longitude;
    private Double latitude;
    private Double altitude;
}

// 航线创建/更新DTO
package com.drone.pojo.dto;

import lombok.Data;
import java.util.List;

@Data
public class RouteDto {
    private String routeName;
    private String djiId;
    private String userName;
    private Double defaultSpeed;
    private Double defaultHeight;
    private String description;
    private List<WaypointDto> waypoints;
}


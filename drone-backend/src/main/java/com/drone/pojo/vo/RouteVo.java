// 航线详情VO
package com.drone.pojo.vo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class RouteVo {
    private Long id;
    private String routeName;
    private String userName;
    private Long uavId;
    private Double defaultSpeed;
    private Double defaultHeight;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<WaypointVo> waypoints;
}


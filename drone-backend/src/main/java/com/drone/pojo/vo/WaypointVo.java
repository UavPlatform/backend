package com.drone.pojo.vo;

import lombok.Data;

// 路径点VO
@Data
public class WaypointVo {
    private Long id;
    private Integer orderIndex;
    private Double longitude;
    private Double latitude;
    private Double altitude;
    private Integer stayTime;
}

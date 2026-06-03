package com.uav.route.pojo.vo;

import com.uav.route.pojo.entity.RouteWaypoint;
import lombok.Data;

@Data
public class WaypointVo {
    private Long id;
    private Integer orderIndex;
    private Double longitude;
    private Double latitude;
    private Double altitude;
    private Integer stayTime;

    public static WaypointVo from(RouteWaypoint wp) {
        WaypointVo vo = new WaypointVo();
        vo.setId(wp.getId());
        vo.setOrderIndex(wp.getOrderIndex());
        vo.setLongitude(wp.getLongitude());
        vo.setLatitude(wp.getLatitude());
        vo.setAltitude(wp.getAltitude());
        vo.setStayTime(wp.getStayTime());
        return vo;
    }
}

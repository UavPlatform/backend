package com.drone.pojo.vo.route;

import com.drone.pojo.entity.Route;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RouteVo {
    private Long id;
    private String routeNum;
    private String routeName;
    private String userName;
    private String djiId;
    private Double defaultSpeed;
    private Double defaultHeight;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<WaypointVo> waypoints;

    public static RouteVo from(Route route) {
        RouteVo vo = new RouteVo();
        vo.setId(route.getId());
        vo.setRouteNum(route.getRouteNum());
        vo.setRouteName(route.getRouteName());
        vo.setUserName(route.getUserName());
        vo.setDjiId(route.getDjiId());
        vo.setDefaultSpeed(route.getDefaultSpeed());
        vo.setDefaultHeight(route.getDefaultHeight());
        vo.setDescription(route.getDescription());
        vo.setCreateTime(route.getCreateTime());
        vo.setUpdateTime(route.getUpdateTime());
        if (route.getWaypoints() != null) {
            vo.setWaypoints(route.getWaypoints().stream()
                    .map(WaypointVo::from)
                    .toList());
        }
        return vo;
    }
}

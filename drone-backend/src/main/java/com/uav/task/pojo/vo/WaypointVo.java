package com.uav.task.pojo.vo;

import com.uav.task.pojo.entity.TaskWaypoint;
import lombok.Data;

@Data
public class WaypointVo {
    private Long id;
    private Integer orderIndex;
    private Double longitude;
    private Double latitude;
    private Double altitude;

    public static WaypointVo from(TaskWaypoint wp) {
        WaypointVo vo = new WaypointVo();
        vo.setId(wp.getId());
        vo.setOrderIndex(wp.getOrderIndex());
        vo.setLongitude(wp.getLongitude());
        vo.setLatitude(wp.getLatitude());
        vo.setAltitude(wp.getAltitude());
        return vo;
    }
}

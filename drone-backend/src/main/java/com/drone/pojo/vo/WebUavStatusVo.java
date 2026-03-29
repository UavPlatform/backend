package com.drone.pojo.vo;

import lombok.Data;

@Data
public class WebUavStatusVo {
    private Long id;
    private String uavName;
    private String djiId;
    private boolean wsConnected;
    private String liveState;
    private UavRuntimeStatusVo latestStatus;
}

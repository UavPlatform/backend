package com.drone.pojo.vo.admin;

import lombok.Data;

@Data
public class LiveUavVO {
    private String deviceId;
    private String uavName;
    private String roomId;
    private String requestId;
    private long updatedAt;
    private String onlineStatus;
    private String isAvailable;
}

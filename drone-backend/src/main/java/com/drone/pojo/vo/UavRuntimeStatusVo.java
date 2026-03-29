package com.drone.pojo.vo;

import lombok.Data;

@Data
public class UavRuntimeStatusVo {
    private String deviceId;
    private Long uavId;
    private String uavName;
    private double longitude;
    private double latitude;
    private double altitude;
    private double speed;
    private int battery;
    private int flightStatus;
    private String operation;
    private long timestamp;
    private long receivedAt;
    private boolean stale;
}

package com.drone.pojo.dto;

import lombok.Data;

@Data
public class UavStatusDto {
    // 无人机ID
    private Long uavId;
    // 无人机名称
    private String uavName;
    // 经度
    private double longitude;
    // 纬度
    private double latitude;
    // 高度
    private double altitude;
    // 速度
    private double speed;
    // 电池电量
    private int battery;
    // 飞行状态（0：地面，1：飞行中）
    private int flightStatus;
    // 将要执行的操作
    private String operation;
    // 时间戳
    private long timestamp;
}
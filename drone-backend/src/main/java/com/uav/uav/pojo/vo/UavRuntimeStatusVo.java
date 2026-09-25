package com.uav.uav.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "无人机实时运行状态")
public class UavRuntimeStatusVo {
    @Schema(description = "设备ID(DJI ID)")
    private String deviceId;
    @Schema(description = "无人机ID")
    private Long uavId;
    @Schema(description = "无人机名称")
    private String uavName;
    @Schema(description = "经度")
    private double longitude;
    @Schema(description = "纬度")
    private double latitude;
    @Schema(description = "高度，单位：米")
    private double altitude;
    @Schema(description = "速度（设备上报值）")
    private double speed;
    @Schema(description = "电池电量（百分比，0-100）")
    private int battery;
    @Schema(description = "飞行状态（0 地面 / 1 飞行中）")
    private int flightStatus;
    @Schema(description = "当前将要执行的操作（设备上报文案）")
    private String operation;
    @Schema(description = "设备上报时间戳（毫秒）")
    private long timestamp;
    @Schema(description = "服务端收到该状态的时间戳（毫秒）")
    private long receivedAt;
    @Schema(description = "状态是否过期（距最近一次上报超过 90 秒为 true）")
    private boolean stale;
}

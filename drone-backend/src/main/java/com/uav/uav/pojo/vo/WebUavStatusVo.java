package com.uav.uav.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Web 端无人机状态（含连接状态与直播状态）")
public class WebUavStatusVo {
    @Schema(description = "无人机ID")
    private Long id;
    @Schema(description = "无人机名称")
    private String uavName;
    @Schema(description = "设备ID(DJI ID)")
    private String djiId;
    @Schema(description = "设备 WebSocket 是否已连接")
    private boolean wsConnected;
    @Schema(description = "直播会话状态（IDLE 空闲 / STARTING 启动中 / RUNNING 运行中）")
    private String liveState;
    @Schema(description = "最近一次上报的运行状态（设备暂未上报时为 null）")
    private UavRuntimeStatusVo latestStatus;
}

package com.uav.uav.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "开播（启动图传）结果")
public record LiveStartVO(
    @Schema(description = "开播命令请求ID（图传已在运行时为 null）")
    String requestId,
    @Schema(description = "TRTC 房间号")
    String roomId,
    @Schema(description = "设备是否已确认启动图传（false 表示命令已发送、等待设备确认）")
    boolean ackConfirmed,
    @Schema(description = "直播会话状态（IDLE 空闲 / STARTING 启动中 / RUNNING 运行中）")
    String liveState,
    @Schema(description = "结果码（LIVE_STARTED 设备已确认启动 / LIVE_START_PENDING 等待设备确认 / LIVE_ALREADY_RUNNING 图传已在运行中）")
    String code
) {}

package com.uav.live.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "TRTC 拉流凭证")
public record PullCredentialsVO(
    @Schema(description = "TRTC 房间号")
    String roomId,
    @Schema(description = "TRTC 用户ID（服务端由当前登录用户ID生成，客户端不可指定）")
    String userId,
    @Schema(description = "TRTC 用户签名（用于加入房间鉴权）")
    String userSig,
    @Schema(description = "TRTC SDK 应用ID")
    long sdkAppId,
    @Schema(description = "设备 WebSocket 连接地址")
    String wsUrl,
    @Schema(description = "直播会话状态（IDLE 空闲 / STARTING 启动中 / RUNNING 运行中）")
    String liveState,
    @Schema(description = "设备图传是否已在运行（true 表示可直接拉流）")
    boolean ackConfirmed
) {}

package com.uav.live.pojo.vo;

public record PullCredentialsVO(
    String roomId,
    String userId,
    String userSig,
    long sdkAppId,
    String wsUrl,
    String liveState,
    boolean ackConfirmed
) {}

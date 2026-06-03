package com.drone.pojo.vo.live;

public record PullCredentialsVO(
    String roomId,
    String userId,
    String userSig,
    long sdkAppId,
    String wsUrl,
    String liveState,
    boolean ackConfirmed
) {}

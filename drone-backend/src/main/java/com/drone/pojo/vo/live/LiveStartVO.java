package com.drone.pojo.vo.live;

public record LiveStartVO(
    String requestId,
    String roomId,
    boolean ackConfirmed,
    String liveState,
    String code
) {}

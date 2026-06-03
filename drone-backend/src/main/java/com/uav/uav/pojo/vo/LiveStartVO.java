package com.uav.uav.pojo.vo;

public record LiveStartVO(
    String requestId,
    String roomId,
    boolean ackConfirmed,
    String liveState,
    String code
) {}

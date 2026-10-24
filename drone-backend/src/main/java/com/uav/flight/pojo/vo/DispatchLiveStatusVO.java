package com.uav.flight.pojo.vo;
public record DispatchLiveStatusVO(boolean configured, String state, String liveId, String connectionId, long leaseExpiresAt) { }

package com.uav.dispatch.pojo.vo;
public record DispatchPositionVO(long receivedAt, long expiresAt, double accuracyMeters, String coordinateSystem) { }

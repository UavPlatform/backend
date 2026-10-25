package com.uav.dispatch.pojo.vo;
/** Drone actually used, as reported by djifly on connection (ADR-0007 #6/#9); null before the first connection. */
public record DispatchExecutedDroneVO(String serialNumber, String modelCode, String modelName, Long aircraftModelId) { }

package com.uav.dispatch.pojo.vo;
import java.time.LocalDateTime;
public record DispatchNearbyStatusVO(String orderId, long assignmentVersion, String state, double radiusKm,
        long notifiedPilotCount, LocalDateTime waitingSince, LocalDateTime reminderAt, boolean reminderSent,
        boolean systemPushEnabled) { }

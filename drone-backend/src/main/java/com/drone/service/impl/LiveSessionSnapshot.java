package com.drone.service.impl;

import lombok.Data;

@Data
public class LiveSessionSnapshot {
    private String deviceId;
    private String roomId;
    private String requestId;
    private LiveSessionState state = LiveSessionState.IDLE;
    private long updatedAt;
    private long expiresAt;

    public boolean isExpired(long now) {
        return state == LiveSessionState.STARTING && expiresAt > 0 && now > expiresAt;
    }
}

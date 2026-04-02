package com.drone.service;

import com.drone.service.impl.LiveSessionSnapshot;
import java.util.List;

public interface LiveSessionService {
    LiveSessionSnapshot getSnapshot(String deviceId);

    boolean isStarting(String deviceId);

    boolean isRunning(String deviceId);

    void markStarting(String deviceId, String roomId, String requestId, long ttlMillis);

    void markRunning(String deviceId, String roomId, String requestId);

    void markFailed(String deviceId);

    void markStopped(String deviceId);
    
    List<LiveSessionSnapshot> getAllRunningSessions();
}

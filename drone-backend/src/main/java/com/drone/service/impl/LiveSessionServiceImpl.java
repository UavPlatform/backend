package com.drone.service.impl;

import com.drone.service.LiveSessionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LiveSessionServiceImpl implements LiveSessionService {
    private final Map<String, LiveSessionSnapshot> sessions = new ConcurrentHashMap<>();

    @Override
    public LiveSessionSnapshot getSnapshot(String deviceId) {
        LiveSessionSnapshot snapshot = sessions.computeIfAbsent(deviceId, this::newIdleSnapshot);
        if (snapshot.isExpired(System.currentTimeMillis())) {
            snapshot.setState(LiveSessionState.IDLE);
            snapshot.setRequestId(null);
            snapshot.setExpiresAt(0);
        }
        return snapshot;
    }

    @Override
    public boolean isStarting(String deviceId) {
        return getSnapshot(deviceId).getState() == LiveSessionState.STARTING;
    }

    @Override
    public boolean isRunning(String deviceId) {
        return getSnapshot(deviceId).getState() == LiveSessionState.RUNNING;
    }

    @Override
    public void markStarting(String deviceId, String roomId, String requestId, long ttlMillis) {
        LiveSessionSnapshot snapshot = getSnapshot(deviceId);
        snapshot.setDeviceId(deviceId);
        snapshot.setRoomId(roomId);
        snapshot.setRequestId(requestId);
        snapshot.setState(LiveSessionState.STARTING);
        snapshot.setUpdatedAt(System.currentTimeMillis());
        snapshot.setExpiresAt(snapshot.getUpdatedAt() + ttlMillis);
    }

    @Override
    public void markRunning(String deviceId, String roomId, String requestId) {
        LiveSessionSnapshot snapshot = getSnapshot(deviceId);
        snapshot.setDeviceId(deviceId);
        snapshot.setRoomId(roomId);
        snapshot.setRequestId(requestId);
        snapshot.setState(LiveSessionState.RUNNING);
        snapshot.setUpdatedAt(System.currentTimeMillis());
        snapshot.setExpiresAt(0);
    }

    @Override
    public void markFailed(String deviceId) {
        LiveSessionSnapshot snapshot = getSnapshot(deviceId);
        snapshot.setState(LiveSessionState.IDLE);
        snapshot.setUpdatedAt(System.currentTimeMillis());
        snapshot.setExpiresAt(0);
        snapshot.setRequestId(null);
    }

    @Override
    public void markStopped(String deviceId) {
        markFailed(deviceId);
        getSnapshot(deviceId).setRoomId(null);
    }

    private LiveSessionSnapshot newIdleSnapshot(String deviceId) {
        LiveSessionSnapshot snapshot = new LiveSessionSnapshot();
        snapshot.setDeviceId(deviceId);
        snapshot.setUpdatedAt(System.currentTimeMillis());
        return snapshot;
    }

    @Override
    public List<LiveSessionSnapshot> getAllRunningSessions() {
        return sessions.values().stream()
                .filter(snapshot -> snapshot.getState() == LiveSessionState.RUNNING)
                .collect(java.util.stream.Collectors.toList());
    }
}

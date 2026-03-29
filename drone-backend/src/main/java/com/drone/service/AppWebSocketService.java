package com.drone.service;

public interface AppWebSocketService {
    boolean requestConnection(String deviceId);

    boolean isPending(String deviceId);

    void markAsConnected(String deviceId);

    void markAsDisconnected(String deviceId);

    boolean isConnected(String deviceId);
}

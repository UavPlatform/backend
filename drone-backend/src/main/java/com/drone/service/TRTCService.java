package com.drone.service;

public interface TRTCService {
    String generateRoomId(String deviceId);

    String generateUserSig(String deviceId);
}

package com.uav.live.service;

public interface TRTCService {
    String generateRoomId(String deviceId);

    String generateUserSig(String deviceId);

    long getSdkAppId();
}

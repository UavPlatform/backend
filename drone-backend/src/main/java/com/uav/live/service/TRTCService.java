package com.uav.live.service;

public interface TRTCService {
    String generateRoomId(String deviceId);

    String generateUserSig(String deviceId);

    long getSdkAppId();

    /** Room-bound ticket, additive for clients upgraded to advanced permission control. */
    String generatePrivateMapKey(String userId, String roomId, boolean publisher);
}

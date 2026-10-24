package com.uav.flight.pojo.vo;
public record DispatchLiveCredentialsVO(String liveId, String connectionId, long sdkAppId, String roomId,
        String userId, String publisherUserId, String userSig, String privateMapKey, long expiresAt, long leaseExpiresAt) { }

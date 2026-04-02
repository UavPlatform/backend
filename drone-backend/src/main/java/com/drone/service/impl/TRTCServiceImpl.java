package com.drone.service.impl;

import com.drone.server.util.TRTCSignatureUtil;
import com.drone.service.TRTCService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TRTCServiceImpl implements TRTCService {

    @Value("${trtc.sdk-app-id}")
    private long sdkAppId;

    @Value("${trtc.secret-key}")
    private String secretKey;

    @Value("${trtc.room-expire}")
    private int roomExpire;

    /**
     * 生成TRTC UserSig
     * @param userId 用户ID
     * @return UserSig字符串
     */
    public String generateUserSig(String userId) {
        return TRTCSignatureUtil.generateUserSig(sdkAppId, userId, secretKey, roomExpire);
    }

    /**
     * 生成房间ID
     * @param deviceId 设备ID
     * @return 房间ID
     */
    public String generateRoomId(String deviceId) {
        return "drone_" + deviceId;
    }

    /**
     * 获取SDK AppId
     * @return SDK AppId
     */
    public long getSdkAppId() {
        return sdkAppId;
    }
}

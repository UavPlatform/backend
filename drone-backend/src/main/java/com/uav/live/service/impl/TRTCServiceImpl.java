package com.uav.live.service.impl;

import com.uav.live.service.TRTCService;
import com.uav.server.util.TRTCSignatureUtil;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class TRTCServiceImpl implements TRTCService {

    /**
     * -- GETTER --
     *  获取SDK AppId
     *
     * @return SDK AppId
     */
    @Getter
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

    @Override
    public String generatePrivateMapKey(String userId, String roomId, boolean publisher) {
        return new com.tencentyun.TLSSigAPIv2(sdkAppId, secretKey)
                .genPrivateMapKeyWithStringRoomID(userId, roomExpire, roomId, publisher ? 255 : 42);
    }

    /**
     * 生成房间ID
     * @param deviceId 设备ID
     * @return 房间ID
     */
    public String generateRoomId(String deviceId) {
        return "drone_" + deviceId;
    }

}

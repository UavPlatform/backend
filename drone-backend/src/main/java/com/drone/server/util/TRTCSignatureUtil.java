package com.drone.server.util;

import com.tencentyun.TLSSigAPIv2;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TRTCSignatureUtil {

    /**
     * 生成TRTC UserSig
     * @param sdkAppId SDKAppID
     * @param userId 用户ID
     * @param secretKey 密钥
     * @param expire 过期时间（秒）
     * @return UserSig字符串
     */
    public static String generateUserSig(long sdkAppId, String userId, String secretKey, int expire) {
        try {
            //直接使用官方的
            TLSSigAPIv2 tlsSigAPIv2 = new TLSSigAPIv2(sdkAppId, secretKey);
            return tlsSigAPIv2.genUserSig(userId, expire);
        } catch (Exception e) {
            log.error("生成UserSig失败: {}", e.getMessage());
            return null;
        }
    }
}
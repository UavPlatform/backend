package com.drone.server.util;

import com.tencentyun.TLSSigAPIv2;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TRTCSignatureUtil {

    public static String generateUserSig(long sdkAppId, String userId, String secretKey, int expire) {
        try {
            TLSSigAPIv2 tlsSigAPIv2 = new TLSSigAPIv2(sdkAppId, secretKey);
            return tlsSigAPIv2.genUserSig(userId, expire);
        } catch (Exception e) {
            log.error("生成 TRTC UserSig 失败，userId: {}, sdkAppId: {}", userId, sdkAppId, e);
            throw new com.drone.server.exception.BusinessException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    com.drone.pojo.enums.ApiErrorCode.TRTC_CREDENTIAL_FAILED,
                    "生成直播凭证失败，请稍后重试"
            );
        }
    }
}

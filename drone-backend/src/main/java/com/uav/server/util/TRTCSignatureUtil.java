package com.uav.server.util;

import com.tencentyun.TLSSigAPIv2;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TRTCSignatureUtil {

    public static String generateUserSig(long sdkAppId, String userId, String secretKey, int expire) {
        try {
            TLSSigAPIv2 tlsSigAPIv2 = new TLSSigAPIv2(sdkAppId, secretKey);
            return tlsSigAPIv2.genUserSig(userId, expire);
        } catch (Exception e) {
            log.error("生成 TRTC UserSig 失败，userId: {}, sdkAppId: {}", userId, sdkAppId, e);
            throw new BusinessException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiErrorCode.TRTC_CREDENTIAL_FAILED,
                    "生成直播凭证失败，请稍后重试"
            );
        }
    }
}

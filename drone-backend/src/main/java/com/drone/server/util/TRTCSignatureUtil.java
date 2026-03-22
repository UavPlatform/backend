package com.drone.server.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class TRTCSignatureUtil {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * 生成TRTC UserSig
     * @param sdkAppId SDKAppID
     * @param userId 用户ID
     * @param secretKey 密钥
     * @param expire 过期时间（秒）
     * @return UserSig字符串
     */
    public static String generateUserSig(long sdkAppId, String userId, String secretKey, int expire) {
        long currentTime = System.currentTimeMillis() / 1000;
        long expireTime = currentTime + expire;

        String contentToSign = "TLS.VERSION:2.0\n" +
                "TLS.SDKAPPID:" + sdkAppId + "\n" +
                "TLS.USERID:" + userId + "\n" +
                "TLS.EXPIRETIME:" + expireTime + "\n" +
                "TLS.NONCE:" + currentTime;

        try {
            //生成签名
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(secretKeySpec);
            byte[] signatureBytes = mac.doFinal(contentToSign.getBytes(StandardCharsets.UTF_8));

            // Base64编码
            String signature = Base64.encodeBase64String(signatureBytes);

            // 构建最终签名
            Map<String, Object> result = new HashMap<>();
            result.put("sdkAppId", sdkAppId);
            result.put("userId", userId);
            result.put("expire", expireTime);
            result.put("nonce", currentTime);
            result.put("signature", signature);

            return com.alibaba.fastjson.JSON.toJSONString(result);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.info(e.getMessage());
            return null;
        }
    }
}
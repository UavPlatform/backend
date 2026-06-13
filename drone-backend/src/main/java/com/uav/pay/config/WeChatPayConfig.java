package com.uav.pay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "wechat.pay")
public class WeChatPayConfig {
    private String appId;
    private String mchId;
    private String privateKeyPath;
    private String privateCertPath;
    private String mchSerialNo;
    private String apiV3Key;
    private String notifyUrl;
}

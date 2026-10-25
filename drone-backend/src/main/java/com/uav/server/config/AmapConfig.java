package com.uav.server.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class AmapConfig {

    @Value("${amap.key}")
    private String key;

    @Value("${amap.security-key}")
    private String securityKey;

    /** Web 端高德安全代理地址（如 https://host/_AMapService），可为空。 */
    @Value("${amap.service-host:}")
    private String serviceHost;
}

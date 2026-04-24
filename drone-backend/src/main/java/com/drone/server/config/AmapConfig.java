package com.drone.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AmapConfig {

    @Value("${amap.key}")
    private String key;

    @Value("${amap.security-key}")
    private String securityKey;

    public String getKey() {
        return key;
    }

    public String getSecurityKey() {
        return securityKey;
    }
}

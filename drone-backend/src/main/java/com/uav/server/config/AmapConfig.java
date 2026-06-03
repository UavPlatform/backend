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
}

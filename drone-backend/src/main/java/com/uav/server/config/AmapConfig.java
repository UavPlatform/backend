package com.uav.server.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Getter
@Component
public class AmapConfig {

    @Value("${amap.key}")
    private String key;

    @Value("${amap.security-key}")
    private String securityKey;

    @PostConstruct
    public void init() {
        log.info("AmapConfig 加载: key={}", key != null && !key.isEmpty() ? key.substring(0, Math.min(4, key.length())) + "***" : "空/未配置!");
    }
}

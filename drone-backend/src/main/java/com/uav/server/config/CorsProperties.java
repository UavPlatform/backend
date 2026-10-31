package com.uav.server.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器跨域允许的 Origin 模式（子域 API：如 {@code https://droneapi.fuwaki.icu}）。
 *
 * <p>鉴权走 {@code Authorization: Bearer}，不依赖 Cookie，故不开启 credentials。
 * 生产前端源通过配置/环境变量注入；默认含本地 Vite 端口与 {@code https://*.fuwaki.icu}。
 * API 主机自身不必写入本列表。
 *
 * <p>环境变量覆盖示例：{@code CORS_ALLOWED_ORIGIN_PATTERNS_0=https://www.fuwaki.icu}
 */
@Component
@Validated
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    /**
     * 允许的 Origin 模式列表（支持 {@code https://*.fuwaki.icu} 这类通配）。
     * 同时用于 HTTP CORS 与 WebSocket 握手 Origin 校验。
     */
    @NotEmpty
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:4173",
            "http://127.0.0.1:4173",
            "https://*.fuwaki.icu"
    ));

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    /** 供 WebSocket registry 使用的数组视图。 */
    public String[] allowedOriginPatternsArray() {
        return allowedOriginPatterns.toArray(String[]::new);
    }
}

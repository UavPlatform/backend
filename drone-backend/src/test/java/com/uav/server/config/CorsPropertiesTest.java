package com.uav.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {

    @Test
    @DisplayName("默认 Origin 模式含本地 Vite 与 fuwaki 通配")
    void defaultsIncludeLocalAndFuwakiPattern() {
        CorsProperties props = new CorsProperties();
        assertThat(props.getAllowedOriginPatterns())
                .contains(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:4173",
                        "http://127.0.0.1:4173",
                        "https://*.fuwaki.icu");
        assertThat(props.allowedOriginPatternsArray()).hasSize(props.getAllowedOriginPatterns().size());
    }

    @Test
    @DisplayName("可覆盖为生产显式前端源列表")
    void canOverrideWithExplicitFrontends() {
        CorsProperties props = new CorsProperties();
        props.setAllowedOriginPatterns(List.of("https://www.fuwaki.icu", "https://admin.fuwaki.icu"));
        assertThat(props.allowedOriginPatternsArray())
                .containsExactly("https://www.fuwaki.icu", "https://admin.fuwaki.icu");
    }
}

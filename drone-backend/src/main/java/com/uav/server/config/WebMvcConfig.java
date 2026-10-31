package com.uav.server.config;

import com.uav.server.interceptor.JwtInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                        // WS 握手（Boot 4 起握手请求会先经过 MVC handler mapping）：
                        // 鉴权统一由 WsAuthHandshakeInterceptor / WsJwtHandshakeConfigurator 负责，
                        // 支持查询参数传 token，避免 JWT 拦截器按 REST 规则误拒（P0-1）
                        "/ws/**"
                );
    }
}
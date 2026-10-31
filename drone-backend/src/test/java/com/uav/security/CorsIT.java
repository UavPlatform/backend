package com.uav.security;

import com.uav.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 子域 API CORS：前端 Origin（如 localhost / *.fuwaki.icu）访问本服务时的预检与响应头。
 *
 * <p>鉴权为 Bearer，不测 credentials。OPTIONS 不得被 JWT 拦成 401。
 */
class CorsIT extends IntegrationTestBase {

    private static final String LOCAL_ORIGIN = "http://localhost:5173";
    private static final String FUWAKI_ORIGIN = "https://www.fuwaki.icu";
    private static final String EVIL_ORIGIN = "https://evil.example";

    @Test
    @DisplayName("OPTIONS 预检：本地 Vite Origin 放行且带 ACAO")
    void preflightAllowsLocalViteOrigin() throws Exception {
        mockMvc.perform(options("/user/login")
                        .header("Origin", LOCAL_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", LOCAL_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        containsStringIgnoringCase("authorization")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        containsStringIgnoringCase("content-type")));
    }

    @Test
    @DisplayName("OPTIONS 预检：https://*.fuwaki.icu 通配匹配")
    void preflightAllowsFuwakiSubdomain() throws Exception {
        mockMvc.perform(options("/user/login")
                        .header("Origin", FUWAKI_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FUWAKI_ORIGIN));
    }

    @Test
    @DisplayName("OPTIONS 预检：未允许 Origin 不返回 ACAO")
    void preflightRejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/user/login")
                        .header("Origin", EVIL_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("实际请求：允许 Origin 时即使业务 401 也回显 ACAO")
    void actualRequestEchoesAllowedOriginEvenWhenUnauthorized() throws Exception {
        mockMvc.perform(get("/api/aircraft-models").header("Origin", LOCAL_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", LOCAL_ORIGIN));
    }

    @Test
    @DisplayName("OPTIONS 预检不要求 JWT（无 Authorization 亦非 401）")
    void preflightDoesNotRequireJwt() throws Exception {
        mockMvc.perform(options("/admin/uav")
                        .header("Origin", LOCAL_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", LOCAL_ORIGIN));
    }
}

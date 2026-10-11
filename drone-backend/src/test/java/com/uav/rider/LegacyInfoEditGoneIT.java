package com.uav.rider;

import com.uav.server.enums.ApiErrorCode;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 已下线接口 {@code POST /info/edit}（REQ-DJIFLY-001 兼容说明、ADR-0002 旧版客户端短期兼容）：
 * 旧版 djifly/App 调用得到 410 Gone + ENDPOINT_REMOVED + 中文升级提示，而不是 404/401；
 * 且该路径不进入 OpenAPI（冻结规格不新增条目）。
 */
class LegacyInfoEditGoneIT extends IntegrationTestBase {

    private static final String BODY = "{\"age\":30,\"location\":\"浙江湖州\"}";

    @Test
    @DisplayName("飞手携带有效令牌调用 POST /info/edit → 410 ENDPOINT_REMOVED，提示升级并指向 /rider/info")
    void riderGetsGoneWithUpgradeHint() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("legacy"), null);
        mockMvc.perform(post("/info/edit")
                        .header("Authorization", rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(410))
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.ENDPOINT_REMOVED.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("已下线")))
                .andExpect(jsonPath("$.message").value(containsString("升级")))
                .andExpect(jsonPath("$.message").value(containsString("/rider/info")));
    }

    @Test
    @DisplayName("未登录 / 令牌无效也返回 410（不是 401），旧客户端不会陷入反复重新登录")
    void goneRegardlessOfAuthentication() throws Exception {
        mockMvc.perform(post("/info/edit").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.ENDPOINT_REMOVED.getCode()));
        mockMvc.perform(post("/info/edit")
                        .header("Authorization", "Bearer not-a-valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isGone());
        mockMvc.perform(get("/info/edit"))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("JWT 排除只覆盖 /info/edit 本身：同前缀的其他路径仍需鉴权")
    void exclusionDoesNotLeakToSiblingPaths() throws Exception {
        mockMvc.perform(post("/info/edit/x").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("已下线接口不进入 OpenAPI：/v3/api-docs 不含 /info/edit")
    void removedEndpointIsNotInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/info/edit"))));
    }
}

package com.uav.map;

import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /config/map：角色 0/1/2 可取 key，绝不返回 securityKey；服务商 403，匿名 401。 */
class MapConfigIT extends IntegrationTestBase {

    @Test void userPilotAndAdminGetKeyWithoutSecurityKey() throws Exception {
        String[] auths = {accounts().registerUser().authorization(), accounts().registerRider().authorization(),
                accounts().adminLogin().authorization()};
        for (String auth : auths) {
            String body = mockMvc.perform(get("/config/map").header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private"))
                    .andExpect(jsonPath("$.data.key").value("amap-test-key"))
                    .andExpect(jsonPath("$.data.serviceHost").value("https://test.example/_AMapService"))
                    .andExpect(jsonPath("$.data.securityKey").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("amap-test-security-key");
        }
    }

    @Test void providerForbiddenAndAnonymousUnauthorized() throws Exception {
        var admin = accounts().adminLogin();
        String name = UniqueNames.userName("provider");
        mockMvc.perform(post("/equipment/admin/providers").header("Authorization", admin.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"equipment-test-pass\",\"displayName\":\"p\"}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/equipment/provider/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"equipment-test-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("token").asText();
        assertThat(TestAccounts.claims(token).path("role").asInt()).isEqualTo(3);
        mockMvc.perform(get("/config/map").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/config/map")).andExpect(status().isUnauthorized());
    }
}

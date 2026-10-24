package com.uav.security;

import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REQ-ALL-XC-003: rejected HTTP requests must not retain the caller on a reused thread. */
class JwtContextCleanupIT extends IntegrationTestBase {
    private void assertAnonymousContext() {
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getUsername()).isNull();
        assertThat(UserContext.getRole()).isNull();
        assertThat(MDC.get("userId")).isNull();
    }
    @Test void roleRejectionClearsAllIdentityFieldsBeforeNextRequest() throws Exception {
        var user = accounts().registerUser();
        mockMvc.perform(get("/admin/uav").header("Authorization", user.authorization()))
                .andExpect(status().isForbidden());
        assertAnonymousContext();
        mockMvc.perform(get("/admin/uav")).andExpect(status().isUnauthorized());
        assertAnonymousContext();
    }
    @Test void unboundPilotRejectionClearsAllIdentityFields() throws Exception {
        var pilot = accounts().registerRider(UniqueNames.userName("ctx"), null);
        mockMvc.perform(post("/live/rider/req").param("deviceId", UniqueNames.djiId())
                .header("Authorization", pilot.authorization())).andExpect(status().isForbidden());
        assertAnonymousContext();
    }
    @Test void anonymousRequestDiscardsAnIdentityLeftOnTheThread() throws Exception {
        UserContext.setUser(123L, "previous-request", 2);
        MDC.put("userId", "123");
        mockMvc.perform(get("/admin/uav")).andExpect(status().isUnauthorized());
        assertAnonymousContext();
    }
}

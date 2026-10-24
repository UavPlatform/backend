package com.uav.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.IntegrationTestBase;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REQ-PILOT-EXEC-005 / REQ-PROV-ACCT-001 / REQ-PROV-GOV-001. */
class EquipmentIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode postData(String token, String path, Object body) throws Exception {
        var response = mockMvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    private JsonNode getData(String token, String path) throws Exception {
        var response = mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    private JsonNode provider(String admin) throws Exception {
        String name = UniqueNames.userName("provider");
        var created = postData(admin, "/equipment/admin/providers", Map.of("username", name, "password", "equipment-test-pass", "displayName", "测试机场"));
        assertThat(created.has("passwordHash")).isFalse(); assertThat(created.has("password")).isFalse();
        return postData("", "/equipment/provider/login", Map.of("username", name, "password", "equipment-test-pass"));
    }
    private Map<String, Object> drone(String serial) {
        return Map.of("aircraftModelId", fixtures.defaultAircraftModelId(), "serialNumber", serial, "airportName", "测试机场", "latitude", 28.2, "longitude", 113.0);
    }
    @Test void platformProvisionsProviderAndEveryIdentityRemainsScoped() throws Exception {
        var admin = accounts().adminLogin(); var owner = accounts().registerUser();
        var a = provider(admin.token()); var b = provider(admin.token()); String token = a.path("token").asText();
        assertThat(com.uav.support.TestAccounts.claims(token).path("role").asInt()).isEqualTo(3);
        String serial = UniqueNames.djiId();
        String id = postData(token, "/equipment/provider/drones", drone(serial)).path("droneId").asText();
        var updated = new HashMap<>(drone(serial)); updated.put("airportName", "新机场");
        mockMvc.perform(put("/equipment/provider/drones/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(updated)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.airportName").value("新机场"));
        assertThat(getData(token, "/equipment/provider/drones").path("items").size()).isEqualTo(1);
        assertThat(getData(b.path("token").asText(), "/equipment/provider/drones").path("items").size()).isZero();
        mockMvc.perform(post("/equipment/provider/drones/" + id + "/enabled").header("Authorization", "Bearer " + b.path("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isNotFound());
        mockMvc.perform(post("/equipment/provider/drones").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(drone(serial.toLowerCase(Locale.ROOT))))).andExpect(status().isConflict());
        for (String path : List.of("/task/init", "/chat/session/list", "/equipment/admin/providers", "/equipment/pilot/drones", "/admin/users")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            assertThat(com.uav.server.util.UserContext.getUserId()).isNull();
        }
        mockMvc.perform(post("/equipment/admin/providers").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        long providerId = a.path("provider").path("providerId").asLong();
        postData(admin.token(), "/equipment/admin/providers/" + providerId + "/enabled", Map.of("enabled", false));
        mockMvc.perform(get("/equipment/provider/me").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }
    @Test void borrowingDoesNotBindCreateOrdersOrChargeAndReturnIsScopedAndIdempotent() throws Exception {
        var admin = accounts().adminLogin(); var p = provider(admin.token()); String token = p.path("token").asText();
        var pilot = accounts().registerRider(UniqueNames.userName("borrowpilot"), null);
        var other = accounts().registerRider(UniqueNames.userName("borrowpilot"), null);
        String id = postData(token, "/equipment/provider/drones", drone(UniqueNames.djiId())).path("droneId").asText();
        var input = Map.of("droneId", id, "requestKey", UUID.randomUUID().toString());
        var first = postData(pilot.token(), "/equipment/pilot/borrows", input);
        String borrowId = first.path("borrowId").asText();
        mockMvc.perform(put("/equipment/provider/drones/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(drone(UniqueNames.djiId())))).andExpect(status().isConflict());
        assertThat(postData(pilot.token(), "/equipment/pilot/borrows", input).path("borrowId").asText()).isEqualTo(borrowId);
        var card = getData(other.token(), "/equipment/pilot/drones?providerId=" + p.path("provider").path("providerId").asLong()).path("items").get(0);
        assertThat(card.path("borrowed").asBoolean()).isTrue(); assertThat(card.path("currentBorrowId").isNull()).isTrue();
        mockMvc.perform(post("/equipment/pilot/borrows/" + borrowId + "/return").header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
        postData(admin.token(), "/equipment/admin/providers/" + p.path("provider").path("providerId").asLong() + "/enabled", Map.of("enabled", false));
        assertThat(postData(pilot.token(), "/equipment/pilot/borrows/" + borrowId + "/return", Map.of()).path("returnedAt").isNull()).isFalse();
        postData(pilot.token(), "/equipment/pilot/borrows/" + borrowId + "/return", Map.of());
        assertThat(getData(pilot.token(), "/rider/drone/list").size()).isZero();
        assertThat(getData(pilot.token(), "/dispatch/pilot/orders").size()).isZero();
        assertThat(getData(admin.token(), "/equipment/admin/borrows").path("items").size()).isGreaterThanOrEqualTo(1);
        for (String page : List.of("-1", "bad", "1000001")) mockMvc.perform(get("/equipment/pilot/drones?page=" + page)
                .header("Authorization", "Bearer " + pilot.token())).andExpect(status().isBadRequest());
    }
}

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

/** REQ-PILOT-EXEC-005 / REQ-SUP-GOV-005 (ADR-0011: drones are admin-registered, no provider role). */
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
    private Map<String, Object> drone(String serial) {
        return Map.of("aircraftModelId", fixtures.defaultAircraftModelId(), "serialNumber", serial, "airportName", "测试机场", "latitude", 28.2, "longitude", 113.0);
    }
    @Test void providerEndpointsAreGone() throws Exception {
        var admin = accounts().adminLogin();
        for (String path : List.of("/equipment/admin/providers", "/equipment/admin/drones", "/equipment/provider/drones", "/admin/provider/drones")) {
            assertThat(mockMvc.perform(get(path).header("Authorization", admin.authorization())).andReturn().getResponse().getStatus())
                    .as(path).isIn(404, 500);
        }
        mockMvc.perform(post("/equipment/provider/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"password\":\"y\"}")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
    }
    @Test void borrowingDoesNotBindCreateOrdersOrChargeAndReturnIsScopedAndIdempotent() throws Exception {
        var admin = accounts().adminLogin();
        var pilot = accounts().registerRider(UniqueNames.userName("borrowpilot"), null);
        var other = accounts().registerRider(UniqueNames.userName("borrowpilot"), null);
        String id = postData(admin.token(), "/admin/drones", drone(UniqueNames.djiId())).path("droneId").asText();
        var input = Map.of("droneId", id, "requestKey", UUID.randomUUID().toString());
        var first = postData(pilot.token(), "/equipment/pilot/borrows", input);
        String borrowId = first.path("borrowId").asText();
        assertThat(first.path("drone").has("providerId")).isFalse();
        assertThat(postData(pilot.token(), "/equipment/pilot/borrows", input).path("borrowId").asText()).isEqualTo(borrowId);
        JsonNode card = null;
        for (var item : getData(other.token(), "/equipment/pilot/drones").path("items")) if (item.path("droneId").asText().equals(id)) card = item;
        assertThat(card).isNotNull();
        assertThat(card.path("borrowed").asBoolean()).isTrue(); assertThat(card.path("currentBorrowId").isNull()).isTrue();
        mockMvc.perform(post("/equipment/pilot/borrows/" + borrowId + "/return").header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
        postData(admin.token(), "/admin/drones/" + id + "/enabled", Map.of("enabled", false));
        assertThat(postData(pilot.token(), "/equipment/pilot/borrows/" + borrowId + "/return", Map.of()).path("returnedAt").isNull()).isFalse();
        postData(pilot.token(), "/equipment/pilot/borrows/" + borrowId + "/return", Map.of());
        assertThat(getData(pilot.token(), "/rider/drone/list").size()).isZero();
        assertThat(getData(pilot.token(), "/dispatch/pilot/orders").size()).isZero();
        assertThat(getData(admin.token(), "/admin/drones/" + id + "/borrows").path("totalElements").asLong()).isEqualTo(1);
        for (String page : List.of("-1", "bad", "1000001")) mockMvc.perform(get("/equipment/pilot/drones?page=" + page)
                .header("Authorization", "Bearer " + pilot.token())).andExpect(status().isBadRequest());
    }
}

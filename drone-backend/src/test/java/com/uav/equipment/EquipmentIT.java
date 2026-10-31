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

/** REQ-PILOT-EXEC-005 / REQ-SUP-GOV-007: apply by model → admin assigns SN → return without re-approval. */
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
        return Map.of("aircraftModelId", fixtures.defaultAircraftModelId(), "serialNumber", serial);
    }
    private JsonNode apply(String pilotToken, Long modelId, String requestKey) throws Exception {
        return postData(pilotToken, "/equipment/pilot/borrows", Map.of("aircraftModelId", modelId, "requestKey", requestKey));
    }
    private JsonNode approve(String adminToken, String borrowId, String droneId) throws Exception {
        return postData(adminToken, "/admin/borrow-applications/" + borrowId + "/approve", Map.of("droneId", droneId));
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

    @Test void applyApproveReturnAndIdempotentWithoutBindingOrdersOrCharges() throws Exception {
        var admin = accounts().adminLogin();
        var pilot = accounts().registerRider(UniqueNames.userName("borrowpilot"), null);
        var other = accounts().registerRider(UniqueNames.userName("borrowpilot"), null);
        Long modelId = fixtures.defaultAircraftModelId();
        String id = postData(admin.token(), "/admin/drones", drone(UniqueNames.djiId())).path("droneId").asText();

        var stock = getData(pilot.token(), "/equipment/pilot/model-stock");
        assertThat(stock.path("items").isArray()).isTrue();
        assertThat(stock.path("items").toString()).contains("\"aircraftModelId\":" + modelId);

        String key = UUID.randomUUID().toString();
        var pending = apply(pilot.token(), modelId, key);
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("drone").isNull()).isTrue();
        assertThat(pending.path("alreadyHasActiveLoan").asBoolean()).isFalse();
        assertThat(apply(pilot.token(), modelId, key).path("borrowId").asText()).isEqualTo(pending.path("borrowId").asText());

        // Pending does not occupy the airframe.
        JsonNode card = null;
        for (var item : getData(other.token(), "/equipment/pilot/drones").path("items")) if (item.path("droneId").asText().equals(id)) card = item;
        assertThat(card).isNotNull();
        assertThat(card.path("borrowed").asBoolean()).isFalse();

        var loan = approve(admin.token(), pending.path("borrowId").asText(), id);
        assertThat(loan.path("status").asText()).isEqualTo("APPROVED");
        assertThat(loan.path("drone").path("droneId").asText()).isEqualTo(id);
        assertThat(getData(pilot.token(), "/equipment/pilot/borrows/current").path("items")).hasSize(1);

        // Other pilot cannot return; owner can return even after disable; return is idempotent.
        mockMvc.perform(post("/equipment/pilot/borrows/" + loan.path("borrowId").asText() + "/return")
                .header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
        postData(admin.token(), "/admin/drones/" + id + "/enabled", Map.of("enabled", false));
        assertThat(postData(pilot.token(), "/equipment/pilot/borrows/" + loan.path("borrowId").asText() + "/return", Map.of())
                .path("returnedAt").isNull()).isFalse();
        postData(pilot.token(), "/equipment/pilot/borrows/" + loan.path("borrowId").asText() + "/return", Map.of());
        assertThat(getData(pilot.token(), "/equipment/pilot/borrows/current").path("items")).isEmpty();
        assertThat(getData(pilot.token(), "/rider/drone/list").size()).isZero();
        assertThat(getData(pilot.token(), "/dispatch/pilot/orders").size()).isZero();
        assertThat(getData(admin.token(), "/admin/drones/" + id + "/borrows").path("totalElements").asLong()).isEqualTo(1);

        // Notices for admin (pending/out/returned) and pilot (approved).
        assertThat(getData(admin.token(), "/equipment/notifications").path("items").size()).isGreaterThanOrEqualTo(1);
        assertThat(getData(pilot.token(), "/equipment/notifications").path("items").size()).isGreaterThanOrEqualTo(1);

        for (String page : List.of("-1", "bad", "1000001")) mockMvc.perform(get("/equipment/pilot/drones?page=" + page)
                .header("Authorization", "Bearer " + pilot.token())).andExpect(status().isBadRequest());
    }

    @Test void cannotApplyWhenModelHasNoInStockAndSecondLoanWarnsButAllowed() throws Exception {
        var admin = accounts().adminLogin();
        var pilot = accounts().registerRider(UniqueNames.userName("warnpilot"), null);
        Long modelId = fixtures.defaultAircraftModelId();
        mockMvc.perform(post("/equipment/pilot/borrows").header("Authorization", "Bearer " + pilot.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("aircraftModelId", modelId, "requestKey", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());

        String droneId = postData(admin.token(), "/admin/drones", drone(UniqueNames.djiId())).path("droneId").asText();
        String droneId2 = postData(admin.token(), "/admin/drones", drone(UniqueNames.djiId())).path("droneId").asText();
        var first = apply(pilot.token(), modelId, UUID.randomUUID().toString());
        approve(admin.token(), first.path("borrowId").asText(), droneId);

        var response = mockMvc.perform(post("/equipment/pilot/borrows").header("Authorization", "Bearer " + pilot.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("aircraftModelId", modelId, "requestKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var tree = json.readTree(response);
        assertThat(tree.path("message").asText()).contains("建议先归还");
        assertThat(tree.path("data").path("alreadyHasActiveLoan").asBoolean()).isTrue();
        assertThat(tree.path("data").path("status").asText()).isEqualTo("PENDING");

        // Admin may specially approve a second airframe.
        approve(admin.token(), tree.path("data").path("borrowId").asText(), droneId2);
        assertThat(getData(pilot.token(), "/equipment/pilot/borrows/current").path("items")).hasSize(2);
    }

    @Test void rejectShowsReasonAndRandomApproveWorks() throws Exception {
        var admin = accounts().adminLogin();
        var pilot = accounts().registerRider(UniqueNames.userName("rejpilot"), null);
        Long modelId = fixtures.defaultAircraftModelId();
        String droneId = postData(admin.token(), "/admin/drones", drone(UniqueNames.djiId())).path("droneId").asText();
        var pending = apply(pilot.token(), modelId, UUID.randomUUID().toString());
        var rejected = postData(admin.token(), "/admin/borrow-applications/" + pending.path("borrowId").asText() + "/reject",
                Map.of("reason", "机队维保中"));
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("rejectReason").asText()).isEqualTo("机队维保中");

        var next = apply(pilot.token(), modelId, UUID.randomUUID().toString());
        var approved = postData(admin.token(), "/admin/borrow-applications/" + next.path("borrowId").asText() + "/approve",
                Map.of("random", true));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("drone").path("droneId").asText()).isEqualTo(droneId);

        // Mistaken return can be restored by admin.
        postData(pilot.token(), "/equipment/pilot/borrows/" + approved.path("borrowId").asText() + "/return", Map.of());
        var restored = postData(admin.token(), "/admin/borrow-applications/" + approved.path("borrowId").asText() + "/restore", Map.of());
        assertThat(restored.path("status").asText()).isEqualTo("APPROVED");
        assertThat(getData(admin.token(), "/admin/drones/" + droneId).path("borrowStatus").asText()).isEqualTo("BORROWED");
    }
}

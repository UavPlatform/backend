package com.uav.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.mapper.WaybillAssessmentRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.entity.WaybillAssessment;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-0011 / REQ-SUP-GOV-005：管理员登记/编辑/启停无人机，在库/已借出、执飞中、最近订单、借还记录。 */
class AdminDroneIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired DispatchOrderRepository orders;
    @Autowired WaybillAssessmentRepository assessments;

    private Map<String, Object> drone(String serial) {
        var m = new HashMap<String, Object>();
        m.put("aircraftModelId", fixtures.defaultAircraftModelId()); m.put("serialNumber", serial);
        return m;
    }
    private JsonNode send(String method, String auth, String path, Object body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), path).header("Authorization", auth);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String text = mockMvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(text).path("data");
    }
    private void order(Long owner, String serial, String stage, LocalDateTime created) {
        var a = new WaybillAssessment();
        a.setId(UUID.randomUUID().toString()); a.setUserId(owner); a.setFingerprint("fp"); a.setFieldsJson("{}");
        a.setEvaluationJson("{}"); a.setExpiresAt(created.plusDays(1)); assessments.saveAndFlush(a);
        var o = new DispatchOrder();
        o.setId(UUID.randomUUID().toString()); o.setUserId(owner); o.setRequestKey(UUID.randomUUID().toString());
        o.setAssessmentId(a.getId()); o.setFingerprint("fp"); o.setFieldsJson("{}");
        o.setDepositAmount(new BigDecimal("50.00")); o.setPilotServiceFee(new BigDecimal("30.00"));
        o.setStage(stage); o.setFundsStatus("PAID"); o.setCreatedAt(created); o.setUpdatedAt(created);
        o.setExecutedSerialNumber(serial);
        orders.saveAndFlush(o);
    }

    @Test void adminRegistersEditsTogglesAndSeesBorrowExecutingAndLatestOrder() throws Exception {
        var admin = accounts().adminLogin(); String auth = admin.authorization();
        var pilot = accounts().registerRider(UniqueNames.userName("adrpilot"), null);
        var owner = accounts().registerUser();
        String serial = UniqueNames.djiId();
        var created = send("POST", auth, "/admin/drones", drone(" " + serial.toLowerCase(Locale.ROOT) + " "), 200);
        String id = created.path("droneId").asText();
        // Registration stores the canonical form (SerialNumbers.normalize); orders below use the stored value.
        String stored = created.path("serialNumber").asText();
        assertThat(stored).isEqualTo(com.uav.server.util.SerialNumbers.normalize(serial));
        assertThat(created.path("borrowStatus").asText()).isEqualTo("IN_STOCK");
        assertThat(created.path("executing").asBoolean()).isFalse();
        assertThat(created.path("latestOrder").isNull()).isTrue();
        assertThat(created.path("model").path("aircraftModelId").asLong()).isEqualTo(fixtures.defaultAircraftModelId());
        assertThat(created.path("airport").path("airportId").asLong()).isEqualTo(1L);
        assertThat(created.path("airport").path("name").asText()).isEqualTo("默认机场");
        assertThat(created.has("provider")).isFalse();
        assertThat(created.has("latitude")).isFalse();
        assertThat(created.has("longitude")).isFalse();
        assertThat(created.has("airportName")).isFalse();

        // Duplicate serial (case/whitespace-insensitive) is rejected.
        send("POST", auth, "/admin/drones", drone(serial), 409);
        // Required fields / format.
        var noModel = drone(UniqueNames.djiId()); noModel.remove("aircraftModelId");
        send("POST", auth, "/admin/drones", noModel, 400);
        send("POST", auth, "/admin/drones", drone("   "), 400);
        var missingModel = drone(UniqueNames.djiId()); missingModel.put("aircraftModelId", 987654321L);
        send("POST", auth, "/admin/drones", missingModel, 400);

        // Edit serial/model identity when in stock; airport stays default-bound.
        var edit = drone(serial);
        var edited = send("PUT", auth, "/admin/drones/" + id, edit, 200);
        assertThat(edited.path("airport").path("airportId").asLong()).isEqualTo(1L);
        assertThat(edited.path("airport").path("name").asText()).isEqualTo("默认机场");
        assertThat(edited.has("airportName")).isFalse();
        assertThat(edited.has("latitude")).isFalse();
        send("PUT", auth, "/admin/drones/not-a-drone", edit, 404);

        // Pilot borrows: list shows BORROWED with pilot and time.
        var loan = send("POST", pilot.authorization(), "/equipment/pilot/borrows", Map.of("droneId", id, "requestKey", UUID.randomUUID().toString()), 200);
        var row = send("GET", auth, "/admin/drones/" + id, null, 200);
        assertThat(row.path("borrowStatus").asText()).isEqualTo("BORROWED");
        assertThat(row.path("currentBorrow").path("pilotId").asLong()).isEqualTo(pilot.id());
        assertThat(row.path("currentBorrow").path("pilotName").asText()).isNotBlank();
        assertThat(row.path("currentBorrow").path("borrowedAt").isNull()).isFalse();
        // Identity cannot change while borrowed.
        send("PUT", auth, "/admin/drones/" + id, drone(UniqueNames.djiId()), 409);

        // Orders flown with this serial: latest one wins; only PILOT_ARRIVED / IN_TRANSIT mark executing.
        LocalDateTime base = LocalDateTime.of(2031, 5, 1, 8, 0);
        order(owner.id(), stored, "DELIVERY_CONFIRMATION", base.minusHours(1));
        row = send("GET", auth, "/admin/drones/" + id, null, 200);
        assertThat(row.path("executing").asBoolean()).isFalse();
        assertThat(row.path("latestOrder").path("stage").asText()).isEqualTo("DELIVERY_CONFIRMATION");
        order(owner.id(), stored, "COMPLETED", base);
        row = send("GET", auth, "/admin/drones/" + id, null, 200);
        assertThat(row.path("executing").asBoolean()).isFalse();
        assertThat(row.path("latestOrder").path("stage").asText()).isEqualTo("COMPLETED");
        order(owner.id(), stored, "IN_TRANSIT", base.plusHours(1));
        row = send("GET", auth, "/admin/drones/" + id, null, 200);
        assertThat(row.path("executing").asBoolean()).isTrue();
        assertThat(row.path("latestOrder").path("stage").asText()).isEqualTo("IN_TRANSIT");
        String latestId = row.path("latestOrder").path("orderId").asText();
        send("GET", auth, "/admin/dispatch/orders/" + latestId, null, 200);

        // Disable while borrowed: both flags visible, original pilot can still return, nobody can borrow.
        var disabled = send("POST", auth, "/admin/drones/" + id + "/enabled", Map.of("enabled", false), 200);
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        assertThat(disabled.path("borrowStatus").asText()).isEqualTo("BORROWED");
        var otherPilot = accounts().registerRider(UniqueNames.userName("adrother"), null);
        send("POST", pilot.authorization(), "/equipment/pilot/borrows/" + loan.path("borrowId").asText() + "/return", Map.of(), 200);
        send("POST", pilot.authorization(), "/equipment/pilot/borrows", Map.of("droneId", id, "requestKey", UUID.randomUUID().toString()), 409);
        send("POST", otherPilot.authorization(), "/equipment/pilot/borrows", Map.of("droneId", id, "requestKey", UUID.randomUUID().toString()), 409);
        assertThat(send("GET", auth, "/admin/drones/" + id, null, 200).path("borrowStatus").asText()).isEqualTo("IN_STOCK");

        // Borrow history.
        var history = send("GET", auth, "/admin/drones/" + id + "/borrows", null, 200);
        assertThat(history.path("totalElements").asLong()).isEqualTo(1);
        assertThat(history.path("content").get(0).path("returnedAt").isNull()).isFalse();
        assertThat(history.path("content").get(0).path("pilotId").asLong()).isEqualTo(pilot.id());
        send("GET", auth, "/admin/drones/nope/borrows", null, 404);
        // Identity is frozen once borrow history exists.
        send("PUT", auth, "/admin/drones/" + id, drone(UniqueNames.djiId()), 409);

        // List with airport-name filter (matches seed 默认机场), LIKE wildcards escaped.
        var list = send("GET", auth, "/admin/drones?airportName=" + "默认机场", null, 200);
        assertThat(list.path("content").toString()).contains(id);
        assertThat(send("GET", auth, "/admin/drones?size=500", null, 200).path("size").asInt()).isEqualTo(100);
        assertThat(send("GET", auth, "/admin/drones?airportName=100%25_none", null, 200).path("totalElements").asLong()).isZero();
    }

    @Test void pilotArrivedCountsAsExecutingButDeliveryConfirmationDoesNot() throws Exception {
        var admin = accounts().adminLogin(); var owner = accounts().registerUser();
        String a = send("POST", admin.authorization(), "/admin/drones", drone(UniqueNames.djiId()), 200).path("serialNumber").asText();
        String b = send("POST", admin.authorization(), "/admin/drones", drone(UniqueNames.djiId()), 200).path("serialNumber").asText();
        order(owner.id(), a, "PILOT_ARRIVED", LocalDateTime.of(2031, 6, 1, 8, 0));
        order(owner.id(), b, "DELIVERY_CONFIRMATION", LocalDateTime.of(2031, 6, 1, 8, 0));
        var rows = send("GET", admin.authorization(), "/admin/drones?size=100", null, 200).path("content");
        Map<String, JsonNode> bySerial = new HashMap<>();
        for (var r : rows) bySerial.put(r.path("serialNumber").asText(), r);
        assertThat(bySerial.get(a).path("executing").asBoolean()).isTrue();
        assertThat(bySerial.get(a).path("latestOrder").path("stage").asText()).isEqualTo("PILOT_ARRIVED");
        assertThat(bySerial.get(b).path("executing").asBoolean()).isFalse();
        assertThat(bySerial.get(b).path("latestOrder").path("stage").asText()).isEqualTo("DELIVERY_CONFIRMATION");
    }

    @Test void badParamsAreRejected() throws Exception {
        var admin = accounts().adminLogin();
        String[][] bad = {{"page", "-1"}, {"page", "abc"}, {"size", "0"}, {"size", "x"}, {"airportName", "x".repeat(101)}};
        for (String[] p : bad) {
            mockMvc.perform(get("/admin/drones").header("Authorization", admin.authorization()).param(p[0], p[1]))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/admin/drones/any/borrows").header("Authorization", admin.authorization()).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/drones/any/enabled").header("Authorization", admin.authorization())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test void nonAdminsAreForbiddenAndAnonymousUnauthorized() throws Exception {
        var user = accounts().registerUser();
        var pilot = accounts().registerRider();
        for (var acc : new TestAccounts.Account[]{user, pilot}) {
            mockMvc.perform(get("/admin/drones").header("Authorization", acc.authorization())).andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/drones").header("Authorization", acc.authorization())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/drones")).andExpect(status().isUnauthorized());
    }
}

package com.uav.aircraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.mapper.WaybillAssessmentRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.entity.WaybillAssessment;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.server.enums.ApplicationStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REQ-SUP-GOV-006：管理员新增/编辑/启停/删除机型；被引用时只能停用。 */
class AdminAircraftModelIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired DispatchOrderRepository orders;
    @Autowired WaybillAssessmentRepository assessments;
    @Autowired FlightConnectionRepository flights;
    @Autowired TaskApplicationRepository applications;

    private Map<String, Object> body(String code, String name, String payload, boolean transport) {
        var m = new HashMap<String, Object>();
        m.put("modelCode", code); m.put("displayName", name);
        m.put("maxPayloadKg", new BigDecimal(payload)); m.put("transportEnabled", transport);
        return m;
    }

    private JsonNode send(String method, String auth, String path, Object body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), path).header("Authorization", auth);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String text = mockMvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(text).path("data");
    }

    @Test void adminCreatesEditsTogglesListsAndDeletesUnused() throws Exception {
        var admin = accounts().adminLogin(); String auth = admin.authorization();
        String tag = UniqueNames.unique("mdl").replace('_', 'X');
        String code = ("T" + tag).substring(0, Math.min(16, tag.length() + 1));

        var created = send("POST", auth, "/admin/aircraft-models", body(" " + code.toLowerCase(Locale.ROOT) + " ", "测试机型" + tag, "12.50", true), 200);
        long id = created.path("id").asLong();
        assertThat(created.path("modelCode").asText()).isEqualTo(code.toLowerCase(Locale.ROOT).trim());
        assertThat(created.path("displayName").asText()).isEqualTo("测试机型" + tag);
        assertThat(created.path("maxPayloadKg").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(created.path("transportEnabled").asBoolean()).isTrue();
        assertThat(created.path("enabled").asBoolean()).isTrue();
        assertThat(created.path("coefficient").decimalValue()).isEqualByComparingTo("1.000");

        // Duplicate code (case-insensitive) → 409.
        send("POST", auth, "/admin/aircraft-models", body(code.toUpperCase(Locale.ROOT), "x", "1.00", false), 409);
        // Missing / blank fields → 400.
        send("POST", auth, "/admin/aircraft-models", body("  ", "x", "1.00", true), 400);
        var noPayload = body(UniqueNames.unique("c"), "x", "1.00", true); noPayload.remove("maxPayloadKg");
        send("POST", auth, "/admin/aircraft-models", noPayload, 400);

        // Edit.
        var edited = send("PUT", auth, "/admin/aircraft-models/" + id,
                body(code, "改名" + tag, "8.00", false), 200);
        assertThat(edited.path("displayName").asText()).isEqualTo("改名" + tag);
        assertThat(edited.path("maxPayloadKg").decimalValue()).isEqualByComparingTo("8.00");
        assertThat(edited.path("transportEnabled").asBoolean()).isFalse();
        assertThat(edited.path("coefficient").decimalValue()).isEqualByComparingTo("1.000");
        send("PUT", auth, "/admin/aircraft-models/987654321", body(code + "Z", "x", "1.00", true), 404);

        // List includes disabled after toggle; public catalog hides it.
        send("POST", auth, "/admin/aircraft-models/" + id + "/enabled", Map.of("enabled", false), 200);
        var list = send("GET", auth, "/admin/aircraft-models?size=100", null, 200);
        assertThat(list.path("size").asInt()).isLessThanOrEqualTo(100);
        boolean foundDisabled = false;
        for (JsonNode row : list.path("content")) {
            if (row.path("id").asLong() == id) {
                foundDisabled = true;
                assertThat(row.path("enabled").asBoolean()).isFalse();
            }
        }
        assertThat(foundDisabled).isTrue();
        var rider = accounts().registerRider();
        String publicBody = mockMvc.perform(get("/api/aircraft-models").header("Authorization", rider.authorization()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(publicBody).doesNotContain(code);

        // Disabled model cannot be selected when registering a drone.
        send("POST", auth, "/admin/drones", Map.of(
                "aircraftModelId", id, "serialNumber", UniqueNames.djiId(),
                "airportName", "x", "latitude", 28.2, "longitude", 113.0), 400);

        // Re-enable and delete unused.
        send("POST", auth, "/admin/aircraft-models/" + id + "/enabled", Map.of("enabled", true), 200);
        send("DELETE", auth, "/admin/aircraft-models/" + id, null, 200);
        send("GET", auth, "/admin/aircraft-models/" + id, null, 404);
        send("DELETE", auth, "/admin/aircraft-models/987654321", null, 404);
        assertThat(send("GET", auth, "/admin/aircraft-models?size=500", null, 200).path("size").asInt()).isEqualTo(100);
    }

    @Test void referencedModelCannotBeDeleted() throws Exception {
        var admin = accounts().adminLogin(); String auth = admin.authorization();
        String tag = UniqueNames.unique("ref").replace('_', 'R');
        String code = ("R" + tag).substring(0, Math.min(16, tag.length() + 1));
        long id = send("POST", auth, "/admin/aircraft-models", body(code, "引用机型" + tag, "5.00", true), 200).path("id").asLong();

        // Referenced by a platform drone.
        send("POST", auth, "/admin/drones", Map.of(
                "aircraftModelId", id, "serialNumber", UniqueNames.djiId(),
                "airportName", "机场" + tag, "latitude", 28.2, "longitude", 113.0), 200);
        var blocked = mockMvc.perform(delete("/admin/aircraft-models/" + id).header("Authorization", auth))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(blocked).contains("请改为停用");
        // Disable still works.
        assertThat(send("POST", auth, "/admin/aircraft-models/" + id + "/enabled", Map.of("enabled", false), 200)
                .path("enabled").asBoolean()).isFalse();
    }

    @Test void riderUavAndQualificationAndFlightBlockDelete() throws Exception {
        var admin = accounts().adminLogin(); String auth = admin.authorization();
        // Fresh models so we can attach one reference each.
        long uavModel = send("POST", auth, "/admin/aircraft-models",
                body(uniqueCode("U"), "绑机机型", "3.00", true), 200).path("id").asLong();
        long qualModel = send("POST", auth, "/admin/aircraft-models",
                body(uniqueCode("Q"), "资质机型", "3.00", true), 200).path("id").asLong();
        long flightModel = send("POST", auth, "/admin/aircraft-models",
                body(uniqueCode("F"), "执飞机型", "3.00", true), 200).path("id").asLong();

        var pilot = accounts().registerRider(UniqueNames.userName("amref"), null);
        fixtures.bindDrone(pilot.id(), UniqueNames.djiId(), uavModel);
        mockMvc.perform(delete("/admin/aircraft-models/" + uavModel).header("Authorization", auth))
                .andExpect(status().isConflict());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/rider/info")
                        .header("Authorization", pilot.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("qualifications",
                                List.of(Map.of("aircraftModelId", qualModel, "licenseGrade", "VLOS"))))))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/aircraft-models/" + qualModel).header("Authorization", auth))
                .andExpect(status().isConflict());

        var owner = accounts().registerUser();
        String orderId = order(owner.id());
        var c = new FlightConnection();
        c.setId(UUID.randomUUID().toString()); c.setOrderId(orderId); c.setPilotId(pilot.id());
        c.setAssignmentVersion(1); c.setRequestKey(UUID.randomUUID().toString());
        c.setSerialNumber(UniqueNames.djiId()); c.setModelCode("X"); c.setModelName("X");
        c.setAircraftModelId(flightModel); c.setOpenedAt(LocalDateTime.of(2031, 7, 1, 8, 0));
        flights.saveAndFlush(c);
        mockMvc.perform(delete("/admin/aircraft-models/" + flightModel).header("Authorization", auth))
                .andExpect(status().isConflict());
    }

    @Test void taskApplicationBlocksDelete() throws Exception {
        var admin = accounts().adminLogin(); String auth = admin.authorization();
        long modelId = send("POST", auth, "/admin/aircraft-models",
                body(uniqueCode("A"), "应征机型", "3.00", true), 200).path("id").asLong();
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider();
        Task task = fixtures.task(TaskStatus.IDLE, 10.0, owner.id());
        TaskApplication application = new TaskApplication();
        application.setTaskId(task.getId());
        application.setRiderId(pilot.id());
        application.setAircraftModelId(modelId);
        application.setQuotedAmount(new BigDecimal("99.00"));
        application.setStatus(ApplicationStatus.ACTIVE);
        applications.saveAndFlush(application);
        var blocked = mockMvc.perform(delete("/admin/aircraft-models/" + modelId).header("Authorization", auth))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(blocked).contains("请改为停用");
    }

    @Test void executedAircraftModelOnOrderBlocksDelete() throws Exception {
        var admin = accounts().adminLogin(); String auth = admin.authorization();
        long modelId = send("POST", auth, "/admin/aircraft-models",
                body(uniqueCode("E"), "订单执飞机型", "3.00", true), 200).path("id").asLong();
        var owner = accounts().registerUser();
        String orderId = order(owner.id());
        DispatchOrder o = orders.findById(orderId).orElseThrow();
        o.setExecutedAircraftModelId(modelId);
        o.setExecutedModelName("订单执飞机型");
        orders.saveAndFlush(o);
        var blocked = mockMvc.perform(delete("/admin/aircraft-models/" + modelId).header("Authorization", auth))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(blocked).contains("请改为停用");
    }

    @Test void nonAdminsForbiddenAndBadParamsRejected() throws Exception {
        var admin = accounts().adminLogin();
        var user = accounts().registerUser();
        var pilot = accounts().registerRider();
        for (var acc : new TestAccounts.Account[]{user, pilot}) {
            mockMvc.perform(get("/admin/aircraft-models").header("Authorization", acc.authorization()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/aircraft-models").header("Authorization", acc.authorization())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/aircraft-models")).andExpect(status().isUnauthorized());
        // Bounds + type-mismatch (Advice maps MethodArgumentTypeMismatchException → 400).
        for (String[] p : new String[][]{{"page", "-1"}, {"page", "abc"}, {"size", "0"}, {"size", "x"}}) {
            mockMvc.perform(get("/admin/aircraft-models").header("Authorization", admin.authorization()).param(p[0], p[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    private String uniqueCode(String prefix) {
        String tag = UniqueNames.unique(prefix).replace('_', 'X');
        return (prefix + tag).substring(0, Math.min(16, prefix.length() + tag.length()));
    }

    private String order(Long owner) {
        var a = new WaybillAssessment();
        a.setId(UUID.randomUUID().toString()); a.setUserId(owner); a.setFingerprint("fp"); a.setFieldsJson("{}");
        a.setEvaluationJson("{}"); a.setExpiresAt(LocalDateTime.of(2031, 7, 1, 0, 0).plusDays(1));
        assessments.saveAndFlush(a);
        var o = new DispatchOrder();
        o.setId(UUID.randomUUID().toString()); o.setUserId(owner); o.setRequestKey(UUID.randomUUID().toString());
        o.setAssessmentId(a.getId()); o.setFingerprint("fp"); o.setFieldsJson("{}");
        o.setDepositAmount(new BigDecimal("50.00")); o.setPilotServiceFee(new BigDecimal("30.00"));
        o.setStage("COMPLETED"); o.setFundsStatus("PAID");
        LocalDateTime t = LocalDateTime.of(2031, 7, 1, 8, 0);
        o.setCreatedAt(t); o.setUpdatedAt(t);
        return orders.saveAndFlush(o).getId();
    }
}

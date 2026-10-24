package com.uav.dispatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.*;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.service.DispatchNearbyService;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true", "dispatch.nearby.scheduler-enabled=false",
        // Real-protocol cases commit waiting orders; this rollback-based suite needs its own database.
        "spring.datasource.url=jdbc:h2:mem:dispatch_nearby_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class DispatchNearbyIT extends IntegrationTestBase {
    @Autowired DispatchOrderRepository orders;
    @Autowired DispatchPilotPositionRepository positions;
    @Autowired DispatchNearbyService nearby;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Account, String> sessionKeys = new HashMap<>();
    private Account pilot() { return accounts().registerRider(UniqueNames.userName("nearpilot"), null); }
    private JsonNode postData(Account actor, String path, Object body) throws Exception {
        var response = mockMvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    private JsonNode getData(Account actor, String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private Map<String, Object> fix(double lat, double lng, long time) {
        return new HashMap<>(Map.of("latitude", lat, "longitude", lng, "accuracyMeters", 12, "capturedAt", time, "coordinateSystem", "GCJ02", "mocked", false));
    }
    private JsonNode putFix(Account pilot, Map<String, Object> fix) throws Exception {
        if (!sessionKeys.containsKey(pilot)) sessionKeys.put(pilot, postData(pilot, "/dispatch/pilot/position-session", Map.of()).asText());
        fix.put("sessionKey", sessionKeys.get(pilot));
        return json.readTree(mockMvc.perform(put("/dispatch/pilot/position").header("Authorization", "Bearer " + pilot.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fix)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String paid(Account owner) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = postData(owner, "/dispatch/assessments", waybill);
        String id = postData(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill)).path("orderId").asText();
        postData(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of()); return id;
    }
    private JsonNode nearbyStatus(Account owner, String id) throws Exception { return getData(owner, "/dispatch/orders/" + id + "/nearby-dispatch"); }
    @Test void validatesRoleCoordinateSystemMockAndAgeAndReplayCannotRenewLocation() throws Exception {
        var pilot = pilot(); var owner = accounts().registerUser(); long now = System.currentTimeMillis();
        var input = fix(28.2, 113, now); var first = putFix(pilot, input);
        assertThat(putFix(pilot, input).path("expiresAt")).isEqualTo(first.path("expiresAt"));
        assertThat(positions.findAll()).hasSize(1);
        for (var bad : List.of(fix(91, 113, now), fix(28.2, 113, now - 301000), fix(28.2, 113, now + 60000))) {
            bad.put("sessionKey", sessionKeys.get(pilot));
            mockMvc.perform(put("/dispatch/pilot/position").header("Authorization", "Bearer " + pilot.token())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bad))).andExpect(status().isBadRequest());
        }
        for (var change : List.of(Map.of("coordinateSystem", "WGS84"), Map.of("mocked", true))) {
            var bad = fix(28.2, 113, now); bad.putAll(change); bad.put("sessionKey", sessionKeys.get(pilot));
            mockMvc.perform(put("/dispatch/pilot/position").header("Authorization", "Bearer " + pilot.token())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bad))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(put("/dispatch/pilot/position").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isForbidden());
    }
    @Test void onlyFreshNearbyPilotsGetDeduplicatedInboxMessagesAndCountsAreNotPhonePush() throws Exception {
        var owner = accounts().registerUser(); var near = pilot(); var far = pilot(); var expired = pilot();
        putFix(near, fix(28.25, 113, System.currentTimeMillis()));
        putFix(far, fix(28.31, 113, System.currentTimeMillis()));
        putFix(expired, fix(28.2, 113, System.currentTimeMillis()));
        var stale = positions.findAll().stream().filter(p -> p.getLatitude() == 28.2).findFirst().orElseThrow();
        stale.setExpiresAt(System.currentTimeMillis() - 1); positions.saveAndFlush(stale);
        String id = paid(owner); assertThat(nearbyStatus(owner, id).path("notifiedPilotCount").asInt()).isZero();
        for (int i = 0; i < 3; i++) nearby.process(id);
        var notice = getData(near, "/dispatch/notifications").path("items");
        assertThat(notice.size()).isEqualTo(1); assertThat(notice.get(0).path("audience").asText()).isEqualTo("PILOT_OFFER");
        assertThat(getData(far, "/dispatch/notifications").path("items").size()).isZero();
        assertThat(getData(expired, "/dispatch/notifications").path("items").size()).isZero();
        assertThat(nearbyStatus(owner, id).path("notifiedPilotCount").asInt()).isEqualTo(1);
        assertThat(nearbyStatus(owner, id).path("systemPushEnabled").asBoolean()).isFalse();
        assertThat(getData(near, "/dispatch/pilot/nearby-hall").path("items").size()).isEqualTo(1);
        assertThat(getData(far, "/dispatch/pilot/nearby-hall").path("items").size()).isZero();
        assertThat(getData(expired, "/dispatch/pilot/nearby-hall").path("locationState").asText()).isEqualTo("LOCATION_REQUIRED");
        mockMvc.perform(delete("/dispatch/pilot/position").header("Authorization", "Bearer " + near.token())).andExpect(status().isOk());
        assertThat(getData(near, "/dispatch/pilot/nearby-hall").path("locationState").asText()).isEqualTo("LOCATION_REQUIRED");
        mockMvc.perform(get("/dispatch/orders/" + id + "/nearby-dispatch").header("Authorization", "Bearer " + far.token())).andExpect(status().isForbidden());
    }
    @Test void timeoutNotifiesOnceWithoutExpandingOrCancellingAndReleaseStartsANewRound() throws Exception {
        var owner = accounts().registerUser(); var pilot = pilot();
        putFix(pilot, fix(28.2, 113, System.currentTimeMillis())); String id = paid(owner);
        var order = orders.findById(id).orElseThrow(); order.setNearbyWaitingSince(LocalDateTime.now().minusSeconds(601)); orders.saveAndFlush(order);
        nearby.process(id); nearby.process(id);
        var before = nearbyStatus(owner, id); assertThat(before.path("reminderSent").asBoolean()).isTrue();
        assertThat(before.path("radiusKm").asDouble()).isEqualTo(10);
        assertThat(before.path("state").asText()).isEqualTo("WAITING_ACCEPTANCE");
        postData(pilot, "/dispatch/pilot/orders/" + id + "/claim", Map.of("offerRevision", order.getRevision()));
        nearby.process(id);
        postData(pilot, "/dispatch/pilot/orders/" + id + "/release", Map.of("assignmentVersion", 1));
        assertThat(nearbyStatus(owner, id).path("reminderSent").asBoolean()).isFalse();
        nearby.process(id);
        assertThat(nearbyStatus(owner, id).path("assignmentVersion").asLong()).isEqualTo(1);
        assertThat(nearbyStatus(owner, id).path("notifiedPilotCount").asInt()).isEqualTo(1);
        assertThat(getData(pilot, "/dispatch/notifications").path("items").size()).isEqualTo(2);
        postData(owner, "/dispatch/orders/" + id + "/cancel", Map.of()); nearby.process(id);
        assertThat(nearbyStatus(owner, id).path("state").asText()).isEqualTo("CANCELLED");
    }
    @Test void filteringPrecedesPaginationAcrossDatabaseBatchesAndNeverLeaksContacts() throws Exception {
        var owner = accounts().registerUser(); var pilot = pilot(); String id = paid(owner);
        putFix(pilot, fix(28.2, 113, System.currentTimeMillis()));
        var source = orders.findById(id).orElseThrow();
        for (int i = 0; i < 221; i++) {
            var copy = cn.hutool.core.bean.BeanUtil.copyProperties(source, DispatchOrder.class);
            copy.setId(UUID.randomUUID().toString()); copy.setRevision(null); copy.setRequestKey(UUID.randomUUID().toString());
            copy.setCreatedAt(i < 200 ? LocalDateTime.now().minusHours(1) : LocalDateTime.now());
            if (i < 200) copy.setFieldsJson(source.getFieldsJson().replace("28.2", "29.2"));
            orders.save(copy);
        }
        orders.flush();
        var first = getData(pilot, "/dispatch/pilot/nearby-hall?page=0");
        var second = getData(pilot, "/dispatch/pilot/nearby-hall?page=1");
        assertThat(first.path("items").size()).isEqualTo(20); assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(second.path("items").size()).isEqualTo(2); assertThat(second.path("hasNext").asBoolean()).isFalse();
        Set<String> ids = new HashSet<>();
        for (var result : List.of(first, second)) for (var item : result.path("items")) {
            assertThat(ids.add(item.path("offer").path("orderId").asText())).isTrue();
            assertThat(item.path("offer").path("fields").has("start.phone")).isFalse();
            assertThat(item.path("distanceMeters").asDouble()).isZero();
        }
    }
    @Test void lateLocationCannotReviveStoppedSessionAndOldStopCannotClearNewSession() throws Exception {
        var pilot = pilot(); var oldFix = fix(28.2, 113, System.currentTimeMillis()); putFix(pilot, oldFix);
        String oldKey = sessionKeys.get(pilot);
        mockMvc.perform(delete("/dispatch/pilot/position").param("sessionKey", oldKey)
                .header("Authorization", "Bearer " + pilot.token())).andExpect(status().isOk());
        mockMvc.perform(put("/dispatch/pilot/position").header("Authorization", "Bearer " + pilot.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(oldFix))).andExpect(status().isConflict());
        String next = postData(pilot, "/dispatch/pilot/position-session", Map.of()).asText(); sessionKeys.put(pilot, next);
        assertThat(getData(pilot, "/dispatch/pilot/nearby-hall").path("locationState").asText()).isEqualTo("LOCATION_REQUIRED");
        putFix(pilot, fix(28.2, 113, System.currentTimeMillis()));
        mockMvc.perform(delete("/dispatch/pilot/position").param("sessionKey", oldKey)
                .header("Authorization", "Bearer " + pilot.token())).andExpect(status().isOk());
        assertThat(getData(pilot, "/dispatch/pilot/nearby-hall").path("locationState").asText()).isEqualTo("READY");
        mockMvc.perform(put("/dispatch/pilot/position").header("Authorization", "Bearer " + pilot.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(oldFix))).andExpect(status().isConflict());
    }
}

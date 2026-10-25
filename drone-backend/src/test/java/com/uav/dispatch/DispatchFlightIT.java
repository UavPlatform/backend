package com.uav.dispatch;

import com.fasterxml.jackson.databind.*;
import com.uav.support.*;
import com.uav.support.TestAccounts.Account;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REQ-SHIP-EXEC-004 / REQ-PILOT-EXEC-007: real HTTP authorization and persisted nullable samples. */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DispatchFlightIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired com.uav.flight.mapper.FlightSampleRepository samples;
    private JsonNode send(Account actor, String path, Object body, int status) throws Exception {
        var r = mockMvc.perform(post(path).header("Authorization", "Bearer " + actor.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().is(status)).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("data");
    }
    private JsonNode read(Account actor, String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String prepare(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = send(owner, "/dispatch/assessments", waybill, 200);
        String id = send(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill), 200).path("orderId").asText();
        send(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of(), 200);
        JsonNode card = null;
        for (var item : read(pilot, "/dispatch/pilot/hall").path("items")) if (id.equals(item.path("orderId").asText())) card = item;
        assertThat(card).isNotNull();
        send(pilot, path(id) + "/claim", Map.of("offerRevision", card.path("offerRevision").asLong()), 200);
        send(pilot, path(id) + "/arrive", Map.of("assignmentVersion", 1), 200);
        return id;
    }
    private String path(String id) { return "/dispatch/pilot/orders/" + id; }
    private Map<String, Object> connect(String serial) {
        return Map.of("assignmentVersion", 1, "requestKey", UUID.randomUUID().toString(), "serialNumber", serial,
                "modelCode", "FC30", "modelName", "DJI FlyCart 30");
    }
    @Test void nullableSamplesIdempotencyOrderAndReplacementStayScoped() throws Exception {
        var owner = accounts().registerUser(); var p = accounts().registerRider(UniqueNames.userName("flight"), null);
        var stranger = accounts().registerUser(); String id = prepare(owner, p); String endpoint = path(id) + "/flight-connections";
        assertThat(read(owner, "/dispatch/orders/" + id + "/flight").path("latest").isNull()).isTrue();
        var body = connect(UniqueNames.djiId()); var c = send(p, endpoint, body, 200); String cid = c.path("connectionId").asText();
        assertThat(send(p, endpoint, body, 200).path("connectionId").asText()).isEqualTo(cid);
        assertThat(read(owner, "/dispatch/orders/" + id + "/live").path("configured").asBoolean()).isFalse();
        send(p, endpoint + "/" + cid + "/live", Map.of("requestKey", UUID.randomUUID().toString()), 503);
        assertThat(read(owner, "/dispatch/orders/" + id + "/live").path("state").asText()).isEqualTo("UNAVAILABLE");
        String sample = endpoint + "/" + cid + "/samples";
        var empty = send(p, sample, Map.of("sequence", 0, "capturedAt", 1000), 200);
        assertThat(empty.path("battery").isNull()).isTrue(); assertThat(empty.path("altitude").isNull()).isTrue();
        var point = Map.of("sequence", 2, "capturedAt", System.currentTimeMillis(), "latitude", 28.2, "longitude", 113.0, "battery", 0, "speed", 0, "flying", false);
        var stored = send(p, sample, point, 200);
        assertThat(send(p, sample, point, 200).path("sampleId").asLong()).isEqualTo(stored.path("sampleId").asLong());
        send(p, sample, Map.of("sequence", 2, "capturedAt", 3000, "battery", 1), 409);
        send(p, sample, Map.of("sequence", 1, "capturedAt", 2000, "latitude", 29.0, "longitude", 114.0), 200);
        var state = read(owner, "/dispatch/orders/" + id + "/flight");
        assertThat(state.path("latest").path("sequence").asLong()).isEqualTo(2);
        assertThat(state.path("lastPosition").path("sequence").asLong()).isEqualTo(2);
        assertThat(state.path("stale").asBoolean()).isFalse();
        var expired = samples.findById(stored.path("sampleId").asLong()).orElseThrow();
        expired.setReceivedAt(java.time.LocalDateTime.now().minusMinutes(1)); samples.saveAndFlush(expired);
        assertThat(read(owner, "/dispatch/orders/" + id + "/flight").path("stale").asBoolean()).isTrue();
        send(p, sample, Map.of("sequence", 3, "capturedAt", 4000, "latitude", 28.2), 400);
        assertThat(read(owner, "/dispatch/orders/" + id + "/flight/track").path("items").size()).isEqualTo(3);
        mockMvc.perform(get("/dispatch/orders/" + id + "/flight").header("Authorization", "Bearer " + stranger.token())).andExpect(status().isNotFound());
        var unknownModel = new HashMap<>(connect(UniqueNames.djiId()));
        unknownModel.put("modelCode", "NEW_SDK_PRODUCT"); unknownModel.put("modelName", "Uncatalogued aircraft");
        var c2 = send(p, endpoint, unknownModel, 200);
        assertThat(c2.path("aircraftModelId").isNull()).isTrue(); assertThat(c2.path("maxPayloadKg").isNull()).isTrue();
        var history = read(owner, "/dispatch/orders/" + id + "/flight").path("connectionHistory");
        assertThat(history.size()).isEqualTo(2); assertThat(history.get(0).path("endReason").asText()).isEqualTo("REPLACED");
        send(p, endpoint + "/" + cid + "/close", Map.of(), 200);
        send(p, sample, Map.of("sequence", 3, "capturedAt", 4000), 409);
        assertThat(read(owner, "/dispatch/orders/" + id + "/flight").path("connection").path("connectionId").asText()).isEqualTo(c2.path("connectionId").asText());
        send(owner, "/dispatch/orders/" + id + "/cancel", Map.of(), 200);
        assertThat(read(owner, "/dispatch/orders/" + id + "/flight").path("stale").asBoolean()).isTrue();
        send(p, endpoint + "/" + c2.path("connectionId").asText() + "/samples", Map.of("sequence", 0, "capturedAt", 5000), 409);
    }
    @Test void serialExclusionRollsBackReplacementAndReleaseFreesAircraft() throws Exception {
        var a = accounts().registerUser(); var b = accounts().registerUser();
        var p = accounts().registerRider(UniqueNames.userName("flight"), null); var q = accounts().registerRider(UniqueNames.userName("flight"), null);
        String x = prepare(a, p), y = prepare(b, q), serial = UniqueNames.djiId();
        String ep = path(x) + "/flight-connections", eq = path(y) + "/flight-connections";
        send(p, ep, connect(serial), 200);
        var original = send(q, eq, connect(UniqueNames.djiId()), 200);
        send(q, eq, connect(serial), 409);
        assertThat(read(b, "/dispatch/orders/" + y + "/flight").path("connection").path("connectionId").asText()).isEqualTo(original.path("connectionId").asText());
        assertThat(read(b, "/dispatch/orders/" + y + "/flight").path("connection").path("endedAt").isNull()).isTrue();
        send(p, path(x) + "/release", Map.of("assignmentVersion", 1), 200);
        send(q, eq, connect(serial), 200);
        mockMvc.perform(get("/dispatch/orders/" + x + "/flight").header("Authorization", "Bearer " + p.token())).andExpect(status().isNotFound());
        send(p, ep, connect(serial), 409);
    }
    @Test void delayedAndFutureSamplesStayHistoricalUntilFreshDataArrives() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("fresh"), null);
        String id = prepare(owner, pilot);
        String endpoint = path(id) + "/flight-connections";
        String cid = send(pilot, endpoint, connect(UniqueNames.djiId()), 200).path("connectionId").asText();
        String sample = endpoint + "/" + cid + "/samples";
        String view = "/dispatch/orders/" + id + "/flight";
        send(pilot, sample, Map.of("sequence", 0, "capturedAt", System.currentTimeMillis() - 180_000,
                "latitude", 28.2, "longitude", 113.0, "speed", 10, "flying", true), 200);
        assertThat(read(owner, view).path("stale").asBoolean()).isTrue();
        send(pilot, sample, Map.of("sequence", 1, "capturedAt", System.currentTimeMillis() + 180_000), 200);
        assertThat(read(owner, view).path("stale").asBoolean()).isTrue();
        var fresh = Map.of("sequence", 2, "capturedAt", System.currentTimeMillis(), "battery", 80);
        var saved = send(pilot, sample, fresh, 200);
        assertThat(read(owner, view).path("stale").asBoolean()).isFalse();
        var expired = samples.findById(saved.path("sampleId").asLong()).orElseThrow();
        expired.setReceivedAt(java.time.LocalDateTime.now().minusMinutes(1)); samples.saveAndFlush(expired);
        send(pilot, sample, fresh, 200);
        assertThat(read(owner, view).path("stale").asBoolean()).isTrue();
        assertThat(read(owner, "/dispatch/orders/" + id + "/flight/track").path("items").size()).isEqualTo(3);
    }
}

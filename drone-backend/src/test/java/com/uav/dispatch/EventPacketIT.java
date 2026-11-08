package com.uav.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.flight.event.EventPacketSamples;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H2 proves V28 boots. Acceptance of durability is the MySQL E2E, not this class. */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventPacketIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired private JdbcTemplate jdbc;

    @Test
    void h2StoresOnePacketAndRejectsAStructuralBodyWithoutARow() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        String id = prepare(owner, pilot);
        long version = jdbc.queryForObject("SELECT assignment_version FROM dispatch_order WHERE id = ?", Long.class, id);
        var identity = new EventPacketSamples.Identity(id, version, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        byte[] raw = EventPacketSamples.first(identity, UUID.randomUUID().toString(), List.of(new EventPacketSamples.Ev(
                UUID.randomUUID().toString(), System.currentTimeMillis(), "POSITION", EventPacketSamples.position(1, 2))));
        var stored = json.readTree(mockMvc.perform(post("/dispatch/pilot/orders/" + id + "/event-packets")
                .header("Authorization", "Bearer " + pilot.token()).contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String packetId = stored.path("data").path("receipt").path("packetId").asText();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_event_v2_packet WHERE packet_id = ?", Integer.class, packetId)).isEqualTo(1);
        var replay = json.readTree(mockMvc.perform(post("/dispatch/pilot/orders/" + id + "/event-packets")
                .header("Authorization", "Bearer " + pilot.token()).contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replay.path("data").path("receipt")).isEqualTo(stored.path("data").path("receipt"));
        var duplicated = new EventPacketSamples.Ev(UUID.randomUUID().toString(), System.currentTimeMillis(), "POSITION", EventPacketSamples.position(3, 4));
        String structuralId = UUID.randomUUID().toString();
        byte[] structural = EventPacketSamples.first(identity, structuralId, List.of(duplicated, duplicated));
        mockMvc.perform(post("/dispatch/pilot/orders/" + id + "/event-packets")
                .header("Authorization", "Bearer " + pilot.token()).contentType(MediaType.APPLICATION_JSON).content(structural))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_event_v2_packet WHERE packet_id = ?", Integer.class, structuralId)).isZero();
    }

    @Test
    void acceptedPacketRecordsExecutedAircraftAndProjectsTheEvent() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        String id = prepare(owner, pilot);
        long version = jdbc.queryForObject("SELECT assignment_version FROM dispatch_order WHERE id = ?", Long.class, id);
        var identity = new EventPacketSamples.Identity(id, version, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "1581F5BKD225400P0001", "FLIGHT_CONTROLLER", "FC30");
        String eventId = UUID.randomUUID().toString();
        byte[] raw = EventPacketSamples.first(identity, UUID.randomUUID().toString(), List.of(new EventPacketSamples.Ev(
                eventId, System.currentTimeMillis(), "POSITION", EventPacketSamples.position(282282000, 1129388000))));

        mockMvc.perform(post("/dispatch/pilot/orders/" + id + "/event-packets")
                .header("Authorization", "Bearer " + pilot.token()).contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk());

        // ADR-0007 #6/#9: the executed aircraft comes from accepted events now that registration is gone.
        var executed = jdbc.queryForMap("SELECT executed_serial_number, executed_model_code, executed_model_name,"
                + " executed_aircraft_model_id FROM dispatch_order WHERE id = ?", id);
        assertThat(executed.get("executed_serial_number")).isEqualTo("1581F5BKD225400P0001");
        assertThat(executed.get("executed_model_code")).isEqualTo("FC30");
        assertThat(executed.get("executed_model_name")).isEqualTo("DJI FlyCart 30");
        assertThat(executed.get("executed_aircraft_model_id")).isNotNull();
        // The pushed payload is the committed projection row, one row per accepted event.
        var projected = jdbc.queryForMap("SELECT event_type, serial_number, captured_at_ms FROM flight_projection_event"
                + " WHERE order_id = ? AND event_id = ?", id, eventId);
        assertThat(projected.get("event_type")).isEqualTo("POSITION");
        assertThat(new String((byte[]) projected.get("serial_number"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("1581F5BKD225400P0001");
    }

    private String prepare(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        String assessment = send(owner, "/dispatch/assessments", waybill).path("assessmentId").asText();
        String id = send(owner, "/dispatch/orders", Map.of("assessmentId", assessment, "requestKey", UUID.randomUUID().toString(),
                "declarationAccepted", true, "waybill", waybill)).path("orderId").asText();
        send(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of());
        var card = json.readTree(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dispatch/pilot/hall")
                .header("Authorization", "Bearer " + pilot.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        com.fasterxml.jackson.databind.JsonNode offer = null;
        for (var item : card.path("data").path("items")) if (id.equals(item.path("orderId").asText())) offer = item;
        assertThat(offer).isNotNull();
        send(pilot, "/dispatch/pilot/orders/" + id + "/claim", Map.of("offerRevision", offer.path("offerRevision").asLong()));
        return id;
    }

    private com.fasterxml.jackson.databind.JsonNode send(Account actor, String path, Object body) throws Exception {
        var response = mockMvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }
}

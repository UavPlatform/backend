package com.uav.flight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.DispatchTestData;
import com.uav.flight.event.EventPacketSamples;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-SUP-MON-005/007: the three read paths are deterministic views of accepted events.
 *
 * <p>Covers the projection read model itself (aircraft grouping, sparse per-type readings, flight periods,
 * history cursors and the pilot/serial filter), which no other test exercised after the v1 read endpoints
 * were removed.
 */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class FlightProjectionReadIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired private JdbcTemplate jdbc;

    @Test
    void projectionGroupsByAircraftKeepsSparseReadingsAndDerivesFlightPeriods() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("read"), null);
        String orderId = prepare(owner, pilot);
        long version = version(orderId);
        var identity = identity(orderId, version, "1581F5BKD225400P0001");
        long now = System.currentTimeMillis();
        // Two flight periods separated by a landing: only actual state transitions provide boundaries.
        upload(pilot, orderId, identity, now - 60_000, List.of(
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 50_000, "ACTUAL_FLYING_STATE", EventPacketSamples.flying(true)),
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 40_000, "POSITION", EventPacketSamples.position(282282000, 1129388000)),
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 30_000, "BATTERY", EventPacketSamples.battery(760))));
        upload(pilot, orderId, identity, now, List.of(
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 20_000, "ACTUAL_FLYING_STATE", EventPacketSamples.flying(false))));

        JsonNode view = read(owner, "/dispatch/orders/" + orderId + "/flight");

        JsonNode aircraft = view.path("aircraft").get(0);
        assertThat(aircraft.path("serialNumber").asText()).isEqualTo("1581F5BKD225400P0001");
        assertThat(aircraft.path("serialSource").asText()).isEqualTo("FLIGHT_CONTROLLER");
        // Sparse readings keep their own capture time; nothing is merged into a combined sample.
        assertThat(aircraft.path("latest").path("POSITION").path("capturedAt").asLong()).isEqualTo(now - 40_000);
        assertThat(aircraft.path("latest").path("BATTERY").path("capturedAt").asLong()).isEqualTo(now - 30_000);
        assertThat(aircraft.path("latest").path("POSITION").path("payload").path("latitudeDegE7").asLong())
                .isEqualTo(282282000L);
        assertThat(view.path("lastPosition").path("type").asText()).isEqualTo("POSITION");
        assertThat(view.path("projectionRevision").asLong()).isPositive();
        JsonNode period = view.path("flightPeriods").get(0);
        assertThat(period.path("takeoffAt").asLong()).isEqualTo(now - 50_000);
        assertThat(period.path("landedAt").asLong()).isEqualTo(now - 20_000);
    }

    @Test
    void trackReturnsOnlyPositionsWithAChangeSequenceCursor() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("track"), null);
        String orderId = prepare(owner, pilot);
        var identity = identity(orderId, version(orderId), "SN-TRACK");
        long now = System.currentTimeMillis();
        upload(pilot, orderId, identity, now, List.of(
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 3_000, "POSITION", EventPacketSamples.position(100, 200)),
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 2_000, "BATTERY", EventPacketSamples.battery(500)),
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 1_000, "POSITION", EventPacketSamples.position(300, 400))));

        JsonNode first = read(owner, "/dispatch/orders/" + orderId + "/flight/track");
        assertThat(first.path("items")).hasSize(2);
        assertThat(first.path("items").get(0).path("type").asText()).isEqualTo("POSITION");
        assertThat(first.path("items").get(1).path("type").asText()).isEqualTo("POSITION");
        assertThat(first.path("hasNext").asBoolean()).isFalse();

        JsonNode empty = read(owner, "/dispatch/orders/" + orderId + "/flight/track?after=" + first.path("nextAfter").asLong());
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("nextAfter").asLong()).isEqualTo(first.path("nextAfter").asLong());
    }

    @Test
    void historyPagesByCapturedAtAndEventIdAndFiltersByAircraftIdentity() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("hist"), null);
        String orderId = prepare(owner, pilot);
        long version = version(orderId);
        long now = System.currentTimeMillis();
        var first = identity(orderId, version, "SN-A");
        var second = identity(orderId, version, "SN-B");
        String firstEvent = UUID.randomUUID().toString();
        upload(pilot, orderId, first, now, List.of(
                new EventPacketSamples.Ev(firstEvent, now - 3_000, "POSITION", EventPacketSamples.position(100, 200)),
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 2_000, "POSITION", EventPacketSamples.position(300, 400))));
        upload(pilot, orderId, second, now, List.of(
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now - 1_000, "POSITION", EventPacketSamples.position(500, 600))));

        JsonNode page = read(owner, "/dispatch/orders/" + orderId + "/flight/history?limit=2");
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(page.path("nextCapturedAt").asLong()).isEqualTo(now - 2_000);
        String cursor = page.path("nextEventId").asText();

        JsonNode rest = read(owner, "/dispatch/orders/" + orderId + "/flight/history?limit=2"
                + "&afterCapturedAt=" + (now - 2_000) + "&afterEventId=" + cursor);
        assertThat(rest.path("items")).hasSize(1);
        assertThat(rest.path("hasNext").asBoolean()).isFalse();

        JsonNode filtered = read(owner, "/dispatch/orders/" + orderId + "/flight/history"
                + "?serialNumber=SN-A&serialSource=FLIGHT_CONTROLLER");
        assertThat(filtered.path("items")).hasSize(2);
        assertThat(filtered.path("items").get(0).path("eventId").asText()).isEqualTo(firstEvent);
    }

    @Test
    void aircraftFilterRequiresBothIdentityFields() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("half"), null);
        String orderId = prepare(owner, pilot);
        upload(pilot, orderId, identity(orderId, version(orderId), "SN-HALF"), System.currentTimeMillis(), List.of(
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), System.currentTimeMillis(), "POSITION",
                        EventPacketSamples.position(1, 2))));

        mockMvc.perform(get("/dispatch/orders/" + orderId + "/flight/history").param("serialNumber", "SN-HALF")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unrelatedUsersCannotReadTheProjection() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("perm"), null);
        var stranger = accounts().registerUser();
        String orderId = prepare(owner, pilot);

        mockMvc.perform(get("/dispatch/orders/" + orderId + "/flight").header("Authorization", stranger.authorization()))
                .andExpect(status().isNotFound());
    }

    private EventPacketSamples.Identity identity(String orderId, long version, String serial) {
        return new EventPacketSamples.Identity(orderId, version, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), serial, "FLIGHT_CONTROLLER", "FC30");
    }

    private void upload(Account pilot, String orderId, EventPacketSamples.Identity identity, long at,
            List<EventPacketSamples.Ev> events) throws Exception {
        long height = jdbc.queryForObject("SELECT COALESCE(MAX(height),0) + 1 FROM flight_event_v2_packet WHERE chain_id = ?",
                Long.class, identity.chainId());
        byte[] previous = height == 1 ? EventPacketSamples.anchor(identity) : jdbc.queryForObject(
                "SELECT chain_hash FROM flight_event_v2_packet WHERE chain_id = ? ORDER BY height DESC LIMIT 1",
                byte[].class, identity.chainId());
        String prevPacketId = height == 1 ? null : jdbc.queryForObject(
                "SELECT packet_id FROM flight_event_v2_packet WHERE chain_id = ? ORDER BY height DESC LIMIT 1",
                String.class, identity.chainId());
        byte[] raw = EventPacketSamples.packet(identity, UUID.randomUUID().toString(), prevPacketId, height, previous, events);
        mockMvc.perform(post("/dispatch/pilot/orders/" + orderId + "/event-packets")
                        .header("Authorization", "Bearer " + pilot.token())
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk());
    }

    private JsonNode read(Account actor, String path) throws Exception {
        var response = mockMvc.perform(get(path).header("Authorization", actor.authorization()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }

    private long version(String orderId) {
        return jdbc.queryForObject("SELECT assignment_version FROM dispatch_order WHERE id = ?", Long.class, orderId);
    }

    private String prepare(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        String assessment = send(owner, "/dispatch/assessments", waybill).path("assessmentId").asText();
        String id = send(owner, "/dispatch/orders", Map.of("assessmentId", assessment,
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill))
                .path("orderId").asText();
        send(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of());
        JsonNode hall = json.readTree(mockMvc.perform(get("/dispatch/pilot/hall")
                .header("Authorization", pilot.authorization())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode offer = null;
        for (JsonNode item : hall.path("data").path("items")) if (id.equals(item.path("orderId").asText())) offer = item;
        assertThat(offer).isNotNull();
        send(pilot, "/dispatch/pilot/orders/" + id + "/claim", Map.of("offerRevision", offer.path("offerRevision").asLong()));
        return id;
    }

    private JsonNode send(Account actor, String path, Object body) throws Exception {
        var response = mockMvc.perform(post(path).header("Authorization", actor.authorization())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
}

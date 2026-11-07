package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.flight.event.EventHex;
import com.uav.flight.event.EventLimits;
import com.uav.flight.event.EventMysql;
import com.uav.flight.event.EventPacketSamples;
import com.uav.flight.event.EventPacketSchema;
import com.uav.flight.event.EventPacketService;
import com.uav.flight.event.UploadOutcome;
import com.uav.flight.pojo.vo.EventPacketAckVO;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-layer contiguous chain on real MySQL. This bypasses the HTTP 600/min limiter without changing it.
 * The few-second packet cadence and five event types are a test sample only, not a product duration cap.
 */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class EventPacketContiguousChainMysqlE2EIT extends RealProtocolTestBase {
    private static final int PACKET_TARGET = 2_001;
    private static final long CADENCE_MS = 3_000L;
    private static final EventPacketSchema SCHEMA = new EventPacketSchema();

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EventPacketService packets;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) throws Exception {
        var db = EventMysql.http();
        EventMysql.wipe(db);
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::user);
        registry.add("spring.datasource.password", db::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void serviceUploadsTwoThousandOneContiguousPacketsWithoutATotalChainCap() throws Exception {
        assertThat(EventLimits.RATE_LIMIT).isEqualTo(600);
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt-long"), null);
        var order = prepare(owner, pilot);
        var identity = new EventPacketSamples.Identity(order.id, order.version, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "FC30SN-LONG", "FLIGHT_CONTROLLER", "FC30");
        long baseCaptured = System.currentTimeMillis();
        String prevId = null;
        byte[] prevHash = EventPacketSamples.anchor(identity);
        byte[] firstRaw = null;
        EventPacketAckVO firstAck = null;
        EventPacketAckVO lastAck = null;
        for (long height = 1; height <= PACKET_TARGET; height++) {
            String packetId = UUID.randomUUID().toString();
            long captured = baseCaptured + (height - 1) * CADENCE_MS;
            var events = List.of(sampleEvent(captured, height));
            byte[] raw = height == 1
                    ? EventPacketSamples.first(identity, packetId, events)
                    : EventPacketSamples.packet(identity, packetId, prevId, height, prevHash, events);
            var outcome = packets.upload(pilot.id(), order.id, raw);
            assertThat(outcome).isInstanceOf(UploadOutcome.Stored.class);
            var stored = (UploadOutcome.Stored) outcome;
            if (height == 1) {
                firstRaw = raw;
                firstAck = stored.ack();
            }
            lastAck = stored.ack();
            prevId = stored.ack().receipt().packetId();
            prevHash = EventHex.decode(stored.ack().receipt().chainHash(), 32);
        }
        assertThat(lastAck.progress().contiguousHeight()).isEqualTo(PACKET_TARGET);
        assertThat(lastAck.progress().highestReceivedHeight()).isEqualTo(PACKET_TARGET);
        assertThat(lastAck.progress().missingRanges()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE chain_id = ?", identity.chainId())).isEqualTo(PACKET_TARGET);
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", firstAck.receipt().packetId()))
                .isEqualTo(firstRaw);
        var replay = packets.upload(pilot.id(), order.id, firstRaw);
        assertThat(replay).isInstanceOf(UploadOutcome.Stored.class);
        assertThat(((UploadOutcome.Stored) replay).ack().receipt()).isEqualTo(firstAck.receipt());
        assertThat(((UploadOutcome.Stored) replay).ack().progress().contiguousHeight()).isEqualTo(PACKET_TARGET);
    }

    private static EventPacketSamples.Ev sampleEvent(long capturedAt, long height) {
        return switch ((int) (height % 5)) {
            case 0 -> new EventPacketSamples.Ev(UUID.randomUUID().toString(), capturedAt, "POSITION",
                    EventPacketSamples.position(282_000_000L + height, 1_130_000_000L + height));
            case 1 -> new EventPacketSamples.Ev(UUID.randomUUID().toString(), capturedAt, "ALTITUDE",
                    EventPacketSamples.altitude(1_000L + height));
            case 2 -> new EventPacketSamples.Ev(UUID.randomUUID().toString(), capturedAt, "BATTERY",
                    EventPacketSamples.battery(800 + (int) (height % 200)));
            case 3 -> new EventPacketSamples.Ev(UUID.randomUUID().toString(), capturedAt, "VELOCITY",
                    EventPacketSamples.velocity(1_000 + height));
            default -> new EventPacketSamples.Ev(UUID.randomUUID().toString(), capturedAt, "ACTUAL_FLYING_STATE",
                    EventPacketSamples.flying(height % 2 == 0));
        };
    }

    private Order prepare(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = postJson(owner, "/dispatch/assessments", waybill, 200);
        String id = postJson(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill), 200).path("orderId").asText();
        postJson(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of(), 200);
        JsonNode card = null;
        for (var item : get(pilot, "/dispatch/pilot/hall").path("items")) {
            if (id.equals(item.path("orderId").asText())) card = item;
        }
        assertThat(card).isNotNull();
        long version = postJson(pilot, "/dispatch/pilot/orders/" + id + "/claim",
                Map.of("offerRevision", card.path("offerRevision").asLong()), 200).path("assignmentVersion").asLong();
        return new Order(id, version);
    }

    private JsonNode postJson(Account actor, String path, Object body, int status) throws Exception {
        return post(actor, path, json.writeValueAsBytes(body), status).path("data");
    }

    private JsonNode get(Account actor, String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + actor.token()).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    private JsonNode post(Account actor, String path, byte[] body, int status) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + actor.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private byte[] bytes(String sql, Object... args) {
        return jdbc.queryForObject(sql, byte[].class, args);
    }

    private record Order(String id, long version) { }
}

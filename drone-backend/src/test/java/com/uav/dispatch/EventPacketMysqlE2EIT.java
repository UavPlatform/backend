package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.flight.event.EventChainHasher;
import com.uav.flight.event.EventCommitGate;
import com.uav.flight.event.EventHex;
import com.uav.flight.event.EventLimits;
import com.uav.flight.event.EventMysql;
import com.uav.flight.event.EventRepairBudget;
import com.uav.flight.event.EventPacketSamples;
import com.uav.flight.event.EventPacketSchema;
import com.uav.flight.service.TelemetryRetentionJob;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP against disposable MySQL. H2 is not the acceptance database.
 * Upload does not open a flight connection. The contract is still a draft.
 */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class EventPacketMysqlE2EIT extends RealProtocolTestBase {
    private static final EventPacketSchema SCHEMA = new EventPacketSchema();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EventCommitGate gate;
    @Autowired private TelemetryRetentionJob retention;
    @Autowired private EventRepairBudget budget;

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
    void mysqlAcceptsAPacketWithoutAFlightConnection() throws Exception {
        String product = jdbc.queryForObject("SELECT DATABASE()", String.class);
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        System.out.println("[event-http] mysql=" + version + " catalog=" + product);
        assertThat(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName()).toLowerCase()).contains("mysql");
        assertThat(product).isEqualTo("event_http");
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        assertThat(get(owner, "/dispatch/orders/" + order.id).path("stage").asText()).isEqualTo("PILOT_PREPARING");
        assertThat(get(owner, "/dispatch/orders/" + order.id).path("arrivedAt").isNull()).isTrue();
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        byte[] raw = draft.at(1, null, draft.anchor(), pos(System.currentTimeMillis()));
        var ack = post(pilot, order.packets(), raw, 200);
        assertThat(ack.data().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(ack.data().path("receipt").path("durability").asText()).isEqualTo("RAW_AND_RESULTS_COMMITTED");
        assertThat(ack.data().path("receipt").path("results").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(ack.data().path("progress").path("contiguousHeight").asLong()).isEqualTo(1);
        assertThat(ack.data().path("progress").path("projectionRevision").asLong()).isZero();
        assertThat(ack.data().path("progress").path("integrity").asText()).isEqualTo("OPEN");
        String packetId = ack.data().path("receipt").path("packetId").asText();
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", packetId)).isEqualTo(raw);
        assertThat(count("SELECT COUNT(*) FROM dispatch_flight_connection WHERE order_id = ?", order.id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM flight_event_fact WHERE packet_id = ?", packetId)).isEqualTo(1);
    }

    @Test
    void replayWhitespaceConcurrencyAndALostResponseKeepTheOriginalReceipt() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        byte[] raw = draft.at(1, null, draft.anchor(), pos(System.currentTimeMillis()));
        var first = post(pilot, order.packets(), raw, 200);
        var replay = post(pilot, order.packets(), raw, 200);
        assertThat(replay.data().path("receipt")).isEqualTo(first.data().path("receipt"));
        assertThat(replay.data().path("receipt").path("results").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(replay.data().path("progress").path("validationVersion").asLong()).isEqualTo(1);
        byte[] spaced = EventPacketSamples.spaced(raw);
        var again = post(pilot, order.packets(), spaced, 200);
        assertThat(again.data().path("receipt")).isEqualTo(first.data().path("receipt"));
        String packetId = first.data().path("receipt").path("packetId").asText();
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", packetId)).isEqualTo(raw);
        assertThat(EventHex.encode(bytes("SELECT raw_sha256 FROM flight_event_packet WHERE packet_id = ?", packetId)))
                .isEqualTo(EventHex.encode(EventChainHasher.sha256(raw)))
                .isNotEqualTo(EventHex.encode(EventChainHasher.sha256(spaced)));
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(1);
        try {
            Future<Reply> left = pool.submit(() -> { ready.await(10, TimeUnit.SECONDS); return post(pilot, order.packets(), raw, 200); });
            Future<Reply> right = pool.submit(() -> { ready.await(10, TimeUnit.SECONDS); return post(pilot, order.packets(), raw, 200); });
            ready.countDown();
            assertThat(left.get(30, TimeUnit.SECONDS).data().path("receipt")).isEqualTo(first.data().path("receipt"));
            assertThat(right.get(30, TimeUnit.SECONDS).data().path("receipt")).isEqualTo(first.data().path("receipt"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE packet_id = ?", packetId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM flight_event_conflict WHERE packet_id = ?", packetId)).isZero();
        var raced = new Draft(order, "RACE1", "FLIGHT_CONTROLLER", "FC30");
        byte[] created = raced.at(1, null, raced.anchor(), pos(System.currentTimeMillis()));
        var againPool = Executors.newFixedThreadPool(2);
        var open = new CountDownLatch(1);
        try {
            Future<Reply> left = againPool.submit(() -> { open.await(10, TimeUnit.SECONDS); return post(pilot, order.packets(), created, 200); });
            Future<Reply> right = againPool.submit(() -> { open.await(10, TimeUnit.SECONDS); return post(pilot, order.packets(), created, 200); });
            open.countDown();
            Reply one = left.get(30, TimeUnit.SECONDS);
            Reply two = right.get(30, TimeUnit.SECONDS);
            assertThat(one.data().path("receipt")).isEqualTo(two.data().path("receipt"));
            assertThat(one.data().path("receipt").path("results").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        } finally {
            againPool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM flight_event_chain WHERE chain_id = ?", raced.chain)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE chain_id = ?", raced.chain)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM flight_event_conflict WHERE order_id = ? AND packet_id = ?",
                order.id, SCHEMA.parse(created, order.id).packetId())).isZero();
    }

    @Test
    void failedCommitRollsBackEveryRowAndTheRetryThenCommits() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        byte[] raw = draft.at(1, null, draft.anchor(), pos(System.currentTimeMillis()));
        String packetId = SCHEMA.parse(raw, order.id).packetId();
        gate.failNextCommit();
        try {
            var failed = post(pilot, order.packets(), raw, 500);
            assertThat(failed.body().path("errorCode").asText()).isEqualTo("INTERNAL_ERROR");
        } finally {
            gate.clear();
        }
        assertAbsent(packetId, draft.chain);
        var stored = post(pilot, order.packets(), raw, 200);
        assertThat(stored.data().path("receipt").path("packetId").asText()).isEqualTo(packetId);
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE packet_id = ?", packetId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM flight_event_receipt WHERE packet_id = ?", packetId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM flight_event_result WHERE packet_id = ?", packetId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM flight_event_fact WHERE packet_id = ?", packetId)).isEqualTo(1);
    }

    @Test
    void eventIdentityConflictsAndSingleEventRejectsStayInTheOriginalHash() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        long now = System.currentTimeMillis();
        var event = pos(now);
        byte[] first = draft.at(1, null, draft.anchor(), event);
        var accepted = post(pilot, order.packets(), first, 200);
        String firstId = accepted.data().path("receipt").path("packetId").asText();
        var parsed = SCHEMA.parse(first, order.id);
        var duplicate = new EventPacketSamples.Ev(event.eventId(), event.capturedAt(), event.type(), event.payload());
        byte[] second = draft.at(2, parsed.packetId(), parsed.chainHash(), duplicate,
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now + 3_000, "ALTITUDE", EventPacketSamples.altitude(1_000)));
        var mixed = post(pilot, order.packets(), second, 200);
        assertThat(mixed.data().path("receipt").path("results").get(0).path("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(mixed.data().path("receipt").path("results").get(0).path("sourcePacketId").asText()).isEqualTo(firstId);
        assertThat(mixed.data().path("receipt").path("results").get(1).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(count("SELECT COUNT(*) FROM flight_event_fact WHERE event_id = ?", event.eventId())).isEqualTo(1);
        var changed = new EventPacketSamples.Ev(event.eventId(), event.capturedAt(), "POSITION", EventPacketSamples.position(1, 2));
        var secondParsed = SCHEMA.parse(second, order.id);
        byte[] third = draft.at(3, secondParsed.packetId(), secondParsed.chainHash(), changed,
                new EventPacketSamples.Ev(UUID.randomUUID().toString(), now + 6_000, "ACTUAL_FLYING_STATE", EventPacketSamples.flying(true)));
        var conflict = post(pilot, order.packets(), third, 200);
        assertThat(conflict.data().path("receipt").path("results").get(0).path("reason").asText()).isEqualTo("EVENT_ID_CONFLICT");
        assertThat(conflict.data().path("receipt").path("results").get(1).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT packet_id FROM flight_event_fact WHERE event_id = ?", String.class, event.eventId())).isEqualTo(firstId);
        byte[] close = draft.at(4, SCHEMA.parse(third, order.id).packetId(), SCHEMA.parse(third, order.id).chainHash(),
                pos(now + 1_000), pos(now + 3_000));
        var interval = post(pilot, order.packets(), close, 200);
        assertThat(interval.data().path("receipt").path("results").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(interval.data().path("receipt").path("results").get(1).path("outcome").asText()).isEqualTo("ACCEPTED");
        var closeParsed = SCHEMA.parse(close, order.id);
        byte[] later = draft.at(5, closeParsed.packetId(), closeParsed.chainHash(), pos(now + 8_000));
        var laterAck = post(pilot, order.packets(), later, 200);
        assertThat(outcome(laterAck)).isEqualTo("ACCEPTED");
        var laterParsed = SCHEMA.parse(later, order.id);
        byte[] earlier = draft.at(6, laterParsed.packetId(), laterParsed.chainHash(), pos(now + 7_000));
        assertThat(outcome(post(pilot, order.packets(), earlier, 200))).isEqualTo("ACCEPTED");
        assertThat(post(pilot, order.packets(), later, 200).data().path("receipt")).isEqualTo(laterAck.data().path("receipt"));
        var earlierParsed = SCHEMA.parse(earlier, order.id);
        byte[] stale = draft.at(7, earlierParsed.packetId(), earlierParsed.chainHash(), pos(0));
        var window = post(pilot, order.packets(), stale, 200);
        assertThat(window.data().path("receipt").path("results").get(0).path("reason").asText()).isEqualTo("ASSIGNMENT_WINDOW");
        assertThat(count("SELECT COUNT(*) FROM flight_event_fact WHERE packet_id = ?", window.data().path("receipt").path("packetId").asText())).isZero();
        var repeated = pos(now + 20_000);
        String structuralId = UUID.randomUUID().toString();
        byte[] structural = EventPacketSamples.first(draft.identity(), structuralId, List.of(repeated, repeated));
        var rejected = post(pilot, order.packets(), structural, 400);
        assertThat(rejected.body().path("errorCode").asText()).isEqualTo("EVENT_PACKET_MALFORMED");
        assertThat(rejected.body().path("message").asText()).isEqualTo("SCHEMA_DUPLICATE_EVENT");
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE packet_id = ?", structuralId)).isZero();
    }

    @Test
    void laterPacketIsConfirmedBeforeTheHoleAndTheServerRepairsFromStoredBytes() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        long now = System.currentTimeMillis();
        byte[] first = draft.at(1, null, draft.anchor(), pos(now));
        var one = SCHEMA.parse(first, order.id);
        byte[] second = draft.at(2, one.packetId(), one.chainHash(), pos(now + 3_000));
        var two = SCHEMA.parse(second, order.id);
        byte[] third = draft.at(3, two.packetId(), two.chainHash(), pos(now + 6_000));
        var late = post(pilot, order.packets(), third, 200);
        assertThat(late.data().path("progress").path("contiguousHeight").asLong()).isZero();
        assertThat(late.data().path("progress").path("highestReceivedHeight").asLong()).isEqualTo(3);
        assertThat(late.data().path("progress").path("missingRanges").get(0).path("fromHeight").asLong()).isEqualTo(1);
        assertThat(late.data().path("progress").path("missingRanges").get(0).path("toHeight").asLong()).isEqualTo(2);
        assertThat(late.data().path("progress").path("integrity").asText()).isEqualTo("OPEN");
        String thirdId = late.data().path("receipt").path("packetId").asText();
        long decidedAt = late.data().path("receipt").path("decidedAt").asLong();
        post(pilot, order.packets(), first, 200);
        var filled = post(pilot, order.packets(), second, 200);
        assertThat(filled.data().path("progress").path("contiguousHeight").asLong()).isEqualTo(3);
        assertThat(filled.data().path("progress").path("highestReceivedHeight").asLong()).isEqualTo(3);
        assertThat(filled.data().path("progress").path("missingRanges")).isEmpty();
        assertThat(filled.data().path("progress").path("contiguousHash").asText()).isEqualTo(late.data().path("receipt").path("chainHash").asText());
        assertThat(jdbc.queryForObject("SELECT decided_at_ms FROM flight_event_receipt WHERE packet_id = ?", Long.class, thirdId)).isEqualTo(decidedAt);
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", thirdId)).isEqualTo(third);
        assertThat(jdbc.queryForObject("SELECT outcome FROM flight_event_result WHERE packet_id = ?", String.class, thirdId)).isEqualTo("ACCEPTED");
    }

    @Test
    void forksBindingsAndCaseSensitiveSerialsDoNotOverwriteHistory() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "AbC", "FLIGHT_CONTROLLER", "FC30");
        long now = System.currentTimeMillis();
        byte[] first = draft.at(1, null, draft.anchor(), pos(now));
        var accepted = post(pilot, order.packets(), first, 200);
        var one = SCHEMA.parse(first, order.id);
        byte[] linked = draft.at(2, one.packetId(), one.chainHash(), pos(now + 3_000));
        post(pilot, order.packets(), linked, 200);
        var two = SCHEMA.parse(linked, order.id);
        byte[] fork = draft.at(3, UUID.randomUUID().toString(), new byte[32], pos(now + 6_000));
        var forked = post(pilot, order.packets(), fork, 200);
        assertThat(forked.data().path("progress").path("contiguousHeight").asLong()).isEqualTo(2);
        assertThat(forked.data().path("progress").path("highestReceivedHeight").asLong()).isEqualTo(3);
        assertThat(forked.data().path("progress").path("integrity").asText()).isEqualTo("FORKED");
        assertThat(forked.data().path("progress").path("missingRanges")).isEmpty();
        assertThat(forked.data().path("receipt").path("results").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        String forkId = forked.data().path("receipt").path("packetId").asText();
        var replay = post(pilot, order.packets(), fork, 200);
        assertThat(replay.data().path("receipt")).isEqualTo(forked.data().path("receipt"));
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", forkId)).isEqualTo(fork);
        byte[] otherHeight = draft.at(1, null, draft.anchor(), pos(now + 10_000));
        var height = post(pilot, order.packets(), otherHeight, 409);
        assertThat(height.body().path("message").asText()).isEqualTo("HEIGHT_FORK");
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", one.packetId())).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM flight_event_conflict WHERE kind = 'HEIGHT_FORK' AND order_id = ?", order.id)).isEqualTo(1);
        byte[] flipped = flip(first);
        var hash = post(pilot, order.packets(), flipped, 400);
        assertThat(hash.body().path("errorCode").asText()).isEqualTo("EVENT_PACKET_HASH");
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE packet_id = ?", one.packetId())).isEqualTo(1);
        byte[] clash = EventPacketSamples.packet(draft.identity(), one.packetId(), one.packetId(), 8, one.chainHash(),
                List.of(pos(now + 40_000)));
        assertThat(post(pilot, order.packets(), clash, 409).body().path("message").asText()).isEqualTo("PACKET_CONTENT");
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", one.packetId())).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM flight_event_conflict WHERE kind = 'PACKET_CONTENT' AND packet_id = ?", one.packetId())).isEqualTo(1);
        var lower = new Draft(order.id, order.version, draft.source, UUID.randomUUID().toString(), "abc", "FLIGHT_CONTROLLER", "FC30");
        post(pilot, order.packets(), lower.at(1, null, lower.anchor(), pos(now + 12_000)), 200);
        var product = new Draft(order.id, order.version, draft.source, UUID.randomUUID().toString(), "AbC", "PRODUCT", "FC30");
        post(pilot, order.packets(), product.at(1, null, product.anchor(), pos(now + 12_000)), 200);
        assertThat(count("SELECT COUNT(*) FROM flight_event_chain WHERE order_id = ?", order.id)).isEqualTo(3);
        var otherChain = new Draft(order.id, order.version, draft.source, UUID.randomUUID().toString(), "AbC", "FLIGHT_CONTROLLER", "FC30");
        var bound = post(pilot, order.packets(), otherChain.at(1, null, otherChain.anchor(), pos(now + 15_000)), 409);
        assertThat(bound.body().path("message").asText()).isEqualTo("CHAIN_BINDING");
        var mutated = new Draft(order.id, order.version, draft.source, draft.chain, "AbC", "FLIGHT_CONTROLLER", "FC30X");
        var identity = post(pilot, order.packets(), mutated.at(4, two.packetId(), two.chainHash(), pos(now + 18_000)), 409);
        assertThat(identity.body().path("message").asText()).isEqualTo("CHAIN_IDENTITY");
        assertThat(new String(bytes("SELECT model_code FROM flight_event_chain WHERE chain_id = ?", draft.chain), StandardCharsets.UTF_8)).isEqualTo("FC30");
        assertThat(accepted.data().path("receipt").path("results").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void reconciliationPagesEveryHoleAndRejectsAStaleValidationVersion() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        long now = System.currentTimeMillis();
        com.uav.flight.event.ParsedPacket parsed = null;
        long version = 0;
        for (long height = 1; height <= 15; height += 2) {
            byte[] raw = parsed == null ? draft.at(1, null, draft.anchor(), pos(now + height * 3_000))
                    : draft.at(height, parsed.packetId(), parsed.chainHash(), pos(now + height * 3_000));
            var ack = post(pilot, order.packets(), raw, 200);
            version = ack.data().path("progress").path("validationVersion").asLong();
            parsed = SCHEMA.parse(raw, order.id);
        }
        assertThat(parsed).isNotNull();
        var present = jdbc.queryForList("SELECT height FROM flight_event_packet WHERE chain_id = ? ORDER BY height", Long.class, draft.chain);
        var expectedHoles = new ArrayList<Long>();
        for (long height = 1; height <= 15; height++) if (!present.contains(height)) expectedHoles.add(height);
        var seen = new ArrayList<Long>();
        var holes = new ArrayList<Long>();
        Long cursor = null;
        Long missing = null;
        boolean entriesDone = false;
        boolean holesDone = false;
        String observedVersion = null;
        for (int page = 0; page < 20 && !(entriesDone && holesDone); page++) {
            Long entryCursor = cursor;
            Long holeCursor = missing;
            if (entriesDone) entryCursor = 16L;
            if (holesDone) holeCursor = 16L;
            var body = reconcile(draft.chain, version, 1, 15, entryCursor, holeCursor, 2, "null");
            var reply = post(pilot, order.reconcile(), body, 200);
            assertThat(reply.data().path("validationVersion").asLong()).isEqualTo(version);
            observedVersion = reply.data().path("validationVersion").asText();
            if (!entriesDone) for (var entry : reply.data().path("entries")) seen.add(entry.path("height").asLong());
            if (!holesDone) for (var range : reply.data().path("missingRanges")) {
                for (long height = range.path("fromHeight").asLong(); height <= range.path("toHeight").asLong(); height++) holes.add(height);
            }
            if (reply.data().path("nextCursor").isNull()) entriesDone = true;
            else cursor = reply.data().path("nextCursor").asLong();
            if (reply.data().path("missingCursor").isNull()) holesDone = true;
            else missing = reply.data().path("missingCursor").asLong();
        }
        assertThat(entriesDone).isTrue();
        assertThat(holesDone).isTrue();
        assertThat(seen).containsExactlyElementsOf(present);
        assertThat(holes).containsExactlyElementsOf(expectedHoles);
        assertThat(observedVersion).isEqualTo(Long.toString(version));
        var open = post(pilot, order.reconcile(), reconcile(draft.chain, null, 1, 15, null, null, 20, "null"), 200);
        assertThat(open.data().path("validationVersion").asLong()).isEqualTo(version);
        assertThat(open.data().path("commonPrefixHeight").isNull()).isTrue();
        String good = open.data().path("entries").get(0).path("chainHash").asText();
        String prefix = "[{\"height\":1,\"chainHash\":\"" + good + "\"},{\"height\":3,\"chainHash\":\"" + "0".repeat(64) + "\"}]";
        var matched = post(pilot, order.reconcile(), reconcile(draft.chain, version, 1, 15, null, null, 20, prefix), 200);
        assertThat(matched.data().path("commonPrefixHeight").asLong()).isEqualTo(1);
        String wrong = "[{\"height\":1,\"chainHash\":\"" + "0".repeat(64) + "\"}]";
        var missed = post(pilot, order.reconcile(), reconcile(draft.chain, version, 1, 15, null, null, 20, wrong), 200);
        assertThat(missed.data().path("commonPrefixHeight").asLong()).isZero();
        byte[] extra = draft.at(17, parsed.packetId(), parsed.chainHash(), pos(now + 60_000));
        post(pilot, order.packets(), extra, 200);
        var stale = post(pilot, order.reconcile(), reconcile(draft.chain, version, 1, 15, null, null, 2, "null"), 409);
        assertThat(stale.body().path("errorCode").asText()).isEqualTo("EVENT_RECONCILIATION_VERSION_CHANGED");
        assertThat(stale.body().path("message").asText()).contains("RECONCILIATION_VERSION_CHANGED current=" + (version + 1));
        assertThat(stale.body().path("data").isMissingNode() || stale.body().path("data").isNull()).isTrue();
    }

    @Test
    void historicalCaptureFollowsTheAssignmentWindowAndReceiptsStayFixed() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var other = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        long accepted = acceptedAt(order.id, order.version);
        byte[] early = draft.at(1, null, draft.anchor(), fly(accepted - 1));
        var anonymous = post(null, order.packets(), early, 401);
        assertThat(anonymous.body().path("errorCode").asText()).isEqualTo("UNAUTHORIZED");
        assertAbsent(SCHEMA.parse(early, order.id).packetId(), draft.chain);
        var forbidden = post(owner, order.packets(), early, 403);
        assertThat(forbidden.body().path("errorCode").asText()).isEqualTo("FORBIDDEN");
        var stranger = post(other, order.packets(), early, 403);
        assertThat(stranger.body().path("errorCode").asText()).isEqualTo("NO_PERMISSION");
        assertAbsent(SCHEMA.parse(early, order.id).packetId(), draft.chain);
        var unknown = new Draft(order.id, 99, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        byte[] missingVersion = unknown.at(1, null, unknown.anchor(), fly(accepted));
        assertThat(post(pilot, order.packets(), missingVersion, 409).body().path("errorCode").asText())
                .isEqualTo("DISPATCH_ASSIGNMENT_CHANGED");
        assertAbsent(SCHEMA.parse(missingVersion, order.id).packetId(), unknown.chain);
        String zero = new String(early, StandardCharsets.UTF_8).replace("\"assignmentVersion\":" + order.version, "\"assignmentVersion\":0");
        assertThat(post(pilot, order.packets(), zero.getBytes(StandardCharsets.UTF_8), 400).body().path("message").asText()).isEqualTo("SCHEMA_TYPE");
        var tooEarly = post(pilot, order.packets(), early, 200);
        assertThat(outcome(tooEarly)).isEqualTo("REJECTED");
        assertThat(reason(tooEarly)).isEqualTo("ASSIGNMENT_WINDOW");
        assertThat(facts(SCHEMA.parse(early, order.id).packetId())).isZero();
        var one = SCHEMA.parse(early, order.id);
        byte[] kept = draft.at(2, one.packetId(), one.chainHash(), fly(accepted));
        var stored = post(pilot, order.packets(), kept, 200);
        assertThat(outcome(stored)).isEqualTo("ACCEPTED");
        assertThat(facts(SCHEMA.parse(kept, order.id).packetId())).isEqualTo(1);
        var keptParsed = SCHEMA.parse(kept, order.id);
        byte[] ahead = draft.at(3, keptParsed.packetId(), keptParsed.chainHash(),
                fly(System.currentTimeMillis() + EventLimits.CAPTURED_FUTURE_MS + 60_000));
        assertThat(reason(post(pilot, order.packets(), ahead, 200))).isEqualTo("EVENT_TIME_WINDOW");
        postJson(pilot, "/dispatch/pilot/orders/" + order.id + "/release", Map.of("assignmentVersion", order.version), 200);
        long ended = endedAt(order.id, order.version);
        assertThat(ended).isGreaterThan(accepted);
        var aheadParsed = SCHEMA.parse(ahead, order.id);
        byte[] beforeEnd = draft.at(4, aheadParsed.packetId(), aheadParsed.chainHash(), fly(ended - 1));
        var lateAck = post(pilot, order.packets(), beforeEnd, 200);
        assertThat(outcome(lateAck)).isEqualTo("ACCEPTED");
        var beforeParsed = SCHEMA.parse(beforeEnd, order.id);
        byte[] atEnd = draft.at(5, beforeParsed.packetId(), beforeParsed.chainHash(), fly(ended));
        var outside = post(pilot, order.packets(), atEnd, 200);
        assertThat(reason(outside)).isEqualTo("ASSIGNMENT_WINDOW");
        var atEndParsed = SCHEMA.parse(atEnd, order.id);
        byte[] afterEnd = draft.at(6, atEndParsed.packetId(), atEndParsed.chainHash(), fly(ended + 60_000));
        assertThat(reason(post(pilot, order.packets(), afterEnd, 200))).isEqualTo("ASSIGNMENT_WINDOW");
        assertThat(post(pilot, order.packets(), kept, 200).data().path("receipt")).isEqualTo(stored.data().path("receipt"));
        assertThat(post(pilot, order.packets(), early, 200).data().path("receipt")).isEqualTo(tooEarly.data().path("receipt"));
        assertThat(post(pilot, order.packets(), beforeEnd, 200).data().path("receipt")).isEqualTo(lateAck.data().path("receipt"));
    }

    @Test
    void redispatchKeepsTheOldRoundAndRejectsTheOtherPilot() throws Exception {
        var owner = accounts().registerUser();
        var first = accounts().registerRider(UniqueNames.userName("evt"), null);
        var second = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, first);
        var older = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        long accepted = acceptedAt(order.id, order.version);
        byte[] kept = older.at(1, null, older.anchor(), fly(accepted));
        var stored = post(first, order.packets(), kept, 200);
        assertThat(outcome(stored)).isEqualTo("ACCEPTED");
        postJson(first, "/dispatch/pilot/orders/" + order.id + "/release", Map.of("assignmentVersion", order.version), 200);
        long ended = endedAt(order.id, order.version);
        assertThat(ended).isGreaterThan(accepted);
        var one = SCHEMA.parse(kept, order.id);
        byte[] late = older.at(2, one.packetId(), one.chainHash(), fly(ended - 1));
        var lateAck = post(first, order.packets(), late, 200);
        assertThat(outcome(lateAck)).isEqualTo("ACCEPTED");
        var lateParsed = SCHEMA.parse(late, order.id);
        byte[] afterRelease = older.at(3, lateParsed.packetId(), lateParsed.chainHash(), fly(ended + 60_000));
        assertThat(reason(post(first, order.packets(), afterRelease, 200))).isEqualTo("ASSIGNMENT_WINDOW");
        long version2 = claim(second, order.id);
        assertThat(version2).isGreaterThan(order.version);
        var newer = new Draft(order.id, version2, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        long secondAccepted = acceptedAt(order.id, version2);
        byte[] stolen = newer.at(1, null, newer.anchor(), fly(secondAccepted));
        assertThat(post(first, order.packets(), stolen, 403).body().path("errorCode").asText()).isEqualTo("NO_PERMISSION");
        assertAbsent(SCHEMA.parse(stolen, order.id).packetId(), newer.chain);
        var afterParsed = SCHEMA.parse(afterRelease, order.id);
        byte[] backward = older.at(4, afterParsed.packetId(), afterParsed.chainHash(), fly(ended - 1));
        assertThat(post(second, order.packets(), backward, 403).body().path("errorCode").asText()).isEqualTo("NO_PERMISSION");
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE packet_id = ?", SCHEMA.parse(backward, order.id).packetId())).isZero();
        byte[] beforeClaim = newer.at(1, null, newer.anchor(), fly(secondAccepted - 9 * 60_000L));
        var rejected = post(second, order.packets(), beforeClaim, 200);
        assertThat(reason(rejected)).isEqualTo("ASSIGNMENT_WINDOW");
        var opened = SCHEMA.parse(beforeClaim, order.id);
        byte[] current = newer.at(2, opened.packetId(), opened.chainHash(), fly(secondAccepted));
        assertThat(outcome(post(second, order.packets(), current, 200))).isEqualTo("ACCEPTED");
        assertThat(post(first, order.packets(), kept, 200).data().path("receipt")).isEqualTo(stored.data().path("receipt"));
        assertThat(post(first, order.packets(), late, 200).data().path("receipt")).isEqualTo(lateAck.data().path("receipt"));
    }

    @Test
    void cancelKeepsHistoricalCaptureAndRejectsAnotherPilot() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var other = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN2", "FLIGHT_CONTROLLER", "FC30");
        long accepted = acceptedAt(order.id, order.version);
        byte[] kept = draft.at(1, null, draft.anchor(), fly(accepted));
        var before = post(pilot, order.packets(), kept, 200);
        assertThat(outcome(before)).isEqualTo("ACCEPTED");
        postJson(owner, "/dispatch/orders/" + order.id + "/cancel", Map.of(), 200);
        assertThat(post(pilot, order.packets(), kept, 200).data().path("receipt")).isEqualTo(before.data().path("receipt"));
        long ended = endedAt(order.id, order.version);
        assertThat(ended).isGreaterThan(accepted);
        var one = SCHEMA.parse(kept, order.id);
        byte[] historical = draft.at(2, one.packetId(), one.chainHash(), fly(ended - 1));
        var historicalAck = post(pilot, order.packets(), historical, 200);
        assertThat(outcome(historicalAck)).isEqualTo("ACCEPTED");
        var two = SCHEMA.parse(historical, order.id);
        byte[] atEnd = draft.at(3, two.packetId(), two.chainHash(), fly(ended));
        assertThat(reason(post(pilot, order.packets(), atEnd, 200))).isEqualTo("ASSIGNMENT_WINDOW");
        byte[] intrusion = draft.at(4, SCHEMA.parse(atEnd, order.id).packetId(), SCHEMA.parse(atEnd, order.id).chainHash(), fly(ended - 1));
        assertThat(post(other, order.packets(), intrusion, 403).body().path("errorCode").asText()).isEqualTo("NO_PERMISSION");
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE packet_id = ?", SCHEMA.parse(intrusion, order.id).packetId())).isZero();
        byte[] tooEarly = draft.at(4, SCHEMA.parse(atEnd, order.id).packetId(), SCHEMA.parse(atEnd, order.id).chainHash(), fly(accepted - 1));
        var rejected = post(pilot, order.packets(), tooEarly, 200);
        assertThat(reason(rejected)).isEqualTo("ASSIGNMENT_WINDOW");
        assertThat(post(pilot, order.packets(), kept, 200).data().path("receipt")).isEqualTo(before.data().path("receipt"));
        assertThat(post(pilot, order.packets(), historical, 200).data().path("receipt")).isEqualTo(historicalAck.data().path("receipt"));
    }

    @Test
    void firstUploadAfterSevenDaysStaysInsideTheRecordedAssignment() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN7", "FLIGHT_CONTROLLER", "FC30");
        long accepted = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000;
        long ended = accepted + 3_600_000;
        jdbc.update("UPDATE dispatch_assignment SET accepted_at = ?, ended_at = ? WHERE order_id = ? AND assignment_version = ?",
                new Timestamp(accepted), new Timestamp(ended), order.id, order.version);
        long storedAccepted = acceptedAt(order.id, order.version);
        long storedEnded = endedAt(order.id, order.version);
        assertThat(storedEnded).isGreaterThan(storedAccepted);
        assertThat(storedAccepted).isLessThan(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000);
        long during = storedAccepted + (storedEnded - storedAccepted) / 2;
        byte[] kept = draft.at(1, null, draft.anchor(), fly(during));
        var stored = post(pilot, order.packets(), kept, 200);
        assertThat(outcome(stored)).isEqualTo("ACCEPTED");
        var one = SCHEMA.parse(kept, order.id);
        byte[] before = draft.at(2, one.packetId(), one.chainHash(), fly(storedAccepted - 1));
        assertThat(reason(post(pilot, order.packets(), before, 200))).isEqualTo("ASSIGNMENT_WINDOW");
        var beforeParsed = SCHEMA.parse(before, order.id);
        byte[] atEnd = draft.at(3, beforeParsed.packetId(), beforeParsed.chainHash(), fly(storedEnded));
        assertThat(reason(post(pilot, order.packets(), atEnd, 200))).isEqualTo("ASSIGNMENT_WINDOW");
        var atEndParsed = SCHEMA.parse(atEnd, order.id);
        byte[] future = draft.at(4, atEndParsed.packetId(), atEndParsed.chainHash(),
                fly(System.currentTimeMillis() + EventLimits.CAPTURED_FUTURE_MS + 60_000));
        assertThat(reason(post(pilot, order.packets(), future, 200))).isEqualTo("EVENT_TIME_WINDOW");
        assertThat(post(pilot, order.packets(), kept, 200).data().path("receipt")).isEqualTo(stored.data().path("receipt"));
    }

    @Test
    void commonPrefixIgnoresHolesAndForks() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        long now = System.currentTimeMillis();
        byte[] only = draft.at(3, UUID.randomUUID().toString(), draft.anchor(), fly(now));
        var hole = post(pilot, order.packets(), only, 200);
        assertThat(hole.data().path("progress").path("contiguousHeight").asLong()).isZero();
        String isolated = hole.data().path("receipt").path("chainHash").asText();
        var missed = post(pilot, order.reconcile(), reconcile(draft.chain, null, 1, 3, null, null, 10,
                "[{\"height\":3,\"chainHash\":\"" + isolated + "\"}]"), 200);
        assertThat(missed.data().path("commonPrefixHeight").asLong()).isZero();
        var linked = new Draft(order, "FC30SN9", "FLIGHT_CONTROLLER", "FC30");
        byte[] first = linked.at(1, null, linked.anchor(), fly(now));
        var oneAck = post(pilot, order.packets(), first, 200);
        var one = SCHEMA.parse(first, order.id);
        byte[] second = linked.at(2, one.packetId(), one.chainHash(), fly(now));
        post(pilot, order.packets(), second, 200);
        var two = SCHEMA.parse(second, order.id);
        byte[] fork = linked.at(3, UUID.randomUUID().toString(), new byte[32], fly(now));
        var forked = post(pilot, order.packets(), fork, 200);
        assertThat(forked.data().path("progress").path("contiguousHeight").asLong()).isEqualTo(2);
        assertThat(forked.data().path("progress").path("integrity").asText()).isEqualTo("FORKED");
        String prefix = "[{\"height\":1,\"chainHash\":\"" + oneAck.data().path("receipt").path("chainHash").asText()
                + "\"},{\"height\":2,\"chainHash\":\"" + EventHex.encode(two.chainHash())
                + "\"},{\"height\":3,\"chainHash\":\"" + forked.data().path("receipt").path("chainHash").asText() + "\"}]";
        var matched = post(pilot, order.reconcile(), reconcile(linked.chain, null, 1, 3, null, null, 10, prefix), 200);
        assertThat(matched.data().path("commonPrefixHeight").asLong()).isEqualTo(2);
    }

    @Test
    void reconciliationLimitRejectsOverflowBeforeNarrowing() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        post(pilot, order.packets(), draft.at(1, null, draft.anchor(), fly(System.currentTimeMillis())), 200);
        assertThat(post(pilot, order.reconcile(), reconcileLimit(draft.chain, "1"), 200).status()).isEqualTo(200);
        assertThat(post(pilot, order.reconcile(), reconcileLimit(draft.chain, "200"), 200).status()).isEqualTo(200);
        assertLimit(pilot, order, draft.chain, "0");
        assertLimit(pilot, order, draft.chain, "201");
        assertLimit(pilot, order, draft.chain, "2147483648");
        assertLimit(pilot, order, draft.chain, "4294967297");
    }

    @Test
    void unverifiedSpanRejectsAFarHeightAndStillFillsReservedHoles() throws Exception {
        assertThat(budget.span()).isEqualTo(EventLimits.UNVERIFIED_SPAN);
        assertThat(budget.pageSize()).isEqualTo(EventLimits.REPAIR_PAGE);
        assertThat(EventLimits.RATE_LIMIT).isEqualTo(600);
        budget.setForTest(3, 1);
        try {
            var owner = accounts().registerUser();
            var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
            var order = prepare(owner, pilot);
            var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
            long now = System.currentTimeMillis();
            byte[] first = draft.at(1, null, draft.anchor(), fly(now));
            var one = SCHEMA.parse(first, order.id);
            byte[] second = draft.at(2, one.packetId(), one.chainHash(), fly(now));
            var two = SCHEMA.parse(second, order.id);
            byte[] third = draft.at(3, two.packetId(), two.chainHash(), fly(now));
            var three = SCHEMA.parse(third, order.id);
            byte[] fourth = draft.at(4, three.packetId(), three.chainHash(), fly(now));
            byte[] far = draft.at(EventLimits.MAX_HEIGHT, UUID.randomUUID().toString(), draft.anchor(), fly(now));
            assertThat(post(pilot, order.packets(), far, 400).body().path("message").asText()).isEqualTo("LIMIT");
            assertAbsent(SCHEMA.parse(far, order.id).packetId(), draft.chain);
            assertThat(post(pilot, order.packets(), fourth, 400).body().path("message").asText()).isEqualTo("LIMIT");
            assertAbsent(SCHEMA.parse(fourth, order.id).packetId(), draft.chain);
            var parked = post(pilot, order.packets(), third, 200);
            assertThat(parked.data().path("progress").path("contiguousHeight").asLong()).isZero();
            assertThat(parked.data().path("progress").path("missingRanges").get(0).path("fromHeight").asLong()).isEqualTo(1);
            assertThat(parked.data().path("progress").path("missingRanges").get(0).path("toHeight").asLong()).isEqualTo(2);
            var five = SCHEMA.parse(draft.at(5, SCHEMA.parse(fourth, order.id).packetId(), SCHEMA.parse(fourth, order.id).chainHash(), fly(now)), order.id);
            byte[] sixth = draft.at(6, five.packetId(), five.chainHash(), fly(now));
            assertThat(post(pilot, order.packets(), sixth, 400).body().path("message").asText()).isEqualTo("LIMIT");
            assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE packet_id = ?", SCHEMA.parse(sixth, order.id).packetId())).isZero();
            byte[] occupied = draft.at(3, two.packetId(), two.chainHash(), fly(now));
            assertThat(post(pilot, order.packets(), occupied, 409).body().path("message").asText()).isEqualTo("HEIGHT_FORK");
            post(pilot, order.packets(), first, 200);
            var filled = post(pilot, order.packets(), second, 200);
            assertThat(filled.data().path("progress").path("contiguousHeight").asLong()).isEqualTo(3);
            assertThat(filled.data().path("progress").path("contiguousHash").asText()).isEqualTo(EventHex.encode(three.chainHash()));
            assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", three.packetId())).isEqualTo(third);
            assertThat(post(pilot, order.packets(), third, 200).data().path("receipt")).isEqualTo(parked.data().path("receipt"));
            var fourAck = post(pilot, order.packets(), fourth, 200);
            assertThat(fourAck.data().path("progress").path("contiguousHeight").asLong()).isEqualTo(4);
            var four = SCHEMA.parse(fourth, order.id);
            byte[] fifth = draft.at(5, four.packetId(), four.chainHash(), fly(now));
            post(pilot, order.packets(), fifth, 200);
            var fiveParsed = SCHEMA.parse(fifth, order.id);
            byte[] continued = draft.at(6, fiveParsed.packetId(), fiveParsed.chainHash(), fly(now));
            var past = post(pilot, order.packets(), continued, 200);
            assertThat(past.data().path("progress").path("contiguousHeight").asLong()).isEqualTo(6);
            assertThat(post(pilot, order.packets(), far, 400).body().path("message").asText()).isEqualTo("LIMIT");
        } finally {
            budget.reset();
        }
    }

    @Test
    void holeSurvivesSampleCleanupAndContinuesFromStoredBytes() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var order = prepare(owner, pilot);
        var draft = new Draft(order, "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        long now = System.currentTimeMillis();
        byte[] first = draft.at(1, null, draft.anchor(), pos(now));
        var one = SCHEMA.parse(first, order.id);
        byte[] second = draft.at(2, one.packetId(), one.chainHash(), pos(now + 3_000));
        var two = SCHEMA.parse(second, order.id);
        byte[] third = draft.at(3, two.packetId(), two.chainHash(), pos(now + 6_000));
        var late = post(pilot, order.packets(), third, 200);
        String thirdId = late.data().path("receipt").path("packetId").asText();
        assertThat(facts(thirdId)).isEqualTo(1);
        jdbc.update("INSERT INTO dispatch_flight_sample (connection_id, order_id, serial_number, sequence_number, fingerprint, captured_at, received_at) "
                + "VALUES ('evt-hole', ?, 'EVT-HOLE', 1, 'evt-hole', 0, CURRENT_TIMESTAMP(6))", order.id);
        var dropped = retention.maintain();
        assertThat(dropped.dropped()).contains("p_hist");
        assertThat(count("SELECT COUNT(*) FROM dispatch_flight_sample WHERE fingerprint = 'evt-hole'")).isZero();
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", thirdId)).isEqualTo(third);
        assertThat(jdbc.queryForObject("SELECT decided_at_ms FROM flight_event_receipt WHERE packet_id = ?", Long.class, thirdId))
                .isEqualTo(late.data().path("receipt").path("decidedAt").asLong());
        assertThat(facts(thirdId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM flight_event_result WHERE packet_id = ?", String.class, thirdId)).isEqualTo("ACCEPTED");
        post(pilot, order.packets(), first, 200);
        var filled = post(pilot, order.packets(), second, 200);
        assertThat(filled.data().path("progress").path("contiguousHeight").asLong()).isEqualTo(3);
        assertThat(filled.data().path("progress").path("contiguousHash").asText()).isEqualTo(late.data().path("receipt").path("chainHash").asText());
        assertThat(bytes("SELECT raw_bytes FROM flight_event_packet WHERE packet_id = ?", thirdId)).isEqualTo(third);
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE chain_id = ?", draft.chain)).isEqualTo(3);
        assertThat(post(pilot, order.packets(), third, 200).data().path("receipt")).isEqualTo(late.data().path("receipt"));
    }

    private void assertAbsent(String packetId, String chainId) {
        assertThat(count("SELECT COUNT(*) FROM flight_event_packet WHERE packet_id = ?", packetId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM flight_event_receipt WHERE packet_id = ?", packetId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM flight_event_result WHERE packet_id = ?", packetId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM flight_event_fact WHERE packet_id = ?", packetId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM flight_event_chain WHERE chain_id = ?", chainId)).isZero();
    }

    private Order prepare(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = postJson(owner, "/dispatch/assessments", waybill, 200);
        String id = postJson(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill), 200).path("orderId").asText();
        postJson(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of(), 200);
        JsonNode card = null;
        for (var item : get(pilot, "/dispatch/pilot/hall").path("items")) if (id.equals(item.path("orderId").asText())) card = item;
        assertThat(card).isNotNull();
        var claimed = postJson(pilot, "/dispatch/pilot/orders/" + id + "/claim", Map.of("offerRevision", card.path("offerRevision").asLong()), 200);
        long version = claimed.path("assignmentVersion").asLong();
        assertThat(version).isPositive();
        return new Order(id, version);
    }

    private JsonNode postJson(Account actor, String path, Object body, int status) throws Exception {
        return post(actor, path, json.writeValueAsBytes(body), status).data();
    }

    private JsonNode get(Account actor, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + actor.token()).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    private Reply post(Account actor, String path, byte[] body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (actor != null) builder.header("Authorization", "Bearer " + actor.token());
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    private static byte[] reconcile(String chainId, Long version, long from, long to, Long cursor, Long missing, int limit, String prefix) {
        String body = "{\"schemaVersion\":1,\"chainId\":\"" + chainId + "\",\"validationVersion\":" + num(version)
                + ",\"fromHeight\":" + from + ",\"toHeight\":" + to + ",\"cursor\":" + num(cursor)
                + ",\"missingCursor\":" + num(missing) + ",\"limit\":" + limit + ",\"prefix\":" + prefix + "}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String num(Long value) { return value == null ? "null" : value.toString(); }

    private static byte[] reconcileLimit(String chainId, String limit) {
        String body = "{\"schemaVersion\":1,\"chainId\":\"" + chainId + "\",\"validationVersion\":null,\"fromHeight\":1,"
                + "\"toHeight\":1,\"cursor\":null,\"missingCursor\":null,\"limit\":" + limit + ",\"prefix\":null}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private void assertLimit(Account pilot, Order order, String chainId, String limit) throws Exception {
        var rejected = post(pilot, order.reconcile(), reconcileLimit(chainId, limit), 400);
        assertThat(rejected.body().path("message").asText()).isEqualTo("LIMIT");
    }

    private static byte[] flip(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        int at = text.indexOf("\"chainHash\":\"") + "\"chainHash\":\"".length();
        char[] chars = text.toCharArray();
        chars[at] = chars[at] == '0' ? '1' : '0';
        return new String(chars).getBytes(StandardCharsets.UTF_8);
    }

    private static EventPacketSamples.Ev pos(long capturedAt) {
        return new EventPacketSamples.Ev(UUID.randomUUID().toString(), capturedAt, "POSITION",
                EventPacketSamples.position(282_000_000L, 1_130_000_000L));
    }

    private static EventPacketSamples.Ev fly(long capturedAt) {
        return new EventPacketSamples.Ev(UUID.randomUUID().toString(), capturedAt, "ACTUAL_FLYING_STATE",
                EventPacketSamples.flying(true));
    }

    private static String outcome(Reply reply) {
        return reply.data().path("receipt").path("results").get(0).path("outcome").asText();
    }

    private static String reason(Reply reply) {
        return reply.data().path("receipt").path("results").get(0).path("reason").asText();
    }

    private long claim(Account pilot, String orderId) throws Exception {
        JsonNode card = null;
        for (var item : get(pilot, "/dispatch/pilot/hall").path("items")) {
            if (orderId.equals(item.path("orderId").asText())) card = item;
        }
        assertThat(card).isNotNull();
        return postJson(pilot, "/dispatch/pilot/orders/" + orderId + "/claim",
                Map.of("offerRevision", card.path("offerRevision").asLong()), 200).path("assignmentVersion").asLong();
    }

    private long acceptedAt(String orderId, long version) {
        Long value = jdbc.queryForObject(
                "SELECT accepted_at FROM dispatch_assignment WHERE order_id = ? AND assignment_version = ?",
                (rs, n) -> rs.getTimestamp(1).getTime(), orderId, version);
        assertThat(value).isNotNull();
        return value;
    }

    private long endedAt(String orderId, long version) {
        Long value = jdbc.queryForObject(
                "SELECT ended_at FROM dispatch_assignment WHERE order_id = ? AND assignment_version = ?",
                (rs, n) -> rs.getTimestamp(1).getTime(), orderId, version);
        assertThat(value).isNotNull();
        return value;
    }

    private int facts(String packetId) {
        return count("SELECT COUNT(*) FROM flight_event_fact WHERE packet_id = ?", packetId);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private byte[] bytes(String sql, Object... args) {
        return jdbc.queryForObject(sql, byte[].class, args);
    }

    private record Order(String id, long version) {
        String packets() { return "/dispatch/pilot/orders/" + id + "/event-packets"; }
        String reconcile() { return "/dispatch/pilot/orders/" + id + "/event-reconciliation"; }
    }

    private record Reply(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
    }

    private static final class Draft {
        final String orderId;
        final long version;
        final String source;
        final String chain;
        final String serial;
        final String serialSource;
        final String model;

        Draft(Order order, String serial, String serialSource, String model) {
            this(order.id, order.version, UUID.randomUUID().toString(), UUID.randomUUID().toString(), serial, serialSource, model);
        }

        Draft(String orderId, long version, String source, String chain, String serial, String serialSource, String model) {
            this.orderId = orderId;
            this.version = version;
            this.source = source;
            this.chain = chain;
            this.serial = serial;
            this.serialSource = serialSource;
            this.model = model;
        }

        EventPacketSamples.Identity identity() {
            return new EventPacketSamples.Identity(orderId, version, source, chain, serial, serialSource, model);
        }

        byte[] anchor() { return EventPacketSamples.anchor(identity()); }

        byte[] at(long height, String prevId, byte[] prevHash, EventPacketSamples.Ev... events) {
            String packetId = UUID.randomUUID().toString();
            return height == 1 ? EventPacketSamples.first(identity(), packetId, List.of(events))
                    : EventPacketSamples.packet(identity(), packetId, prevId, height, prevHash, List.of(events));
        }
    }
}

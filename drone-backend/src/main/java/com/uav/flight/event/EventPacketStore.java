package com.uav.flight.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** JDBC store. Conflicts are inserted explicitly; nothing uses INSERT IGNORE. */
@Component
public class EventPacketStore {
    private final JdbcTemplate jdbc;

    public EventPacketStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public PacketRow findPacket(String packetId) {
        var rows = jdbc.query("SELECT packet_id, chain_id, order_id, uploader_pilot_id, height, content_digest, chain_hash, decided_at_ms "
                + "FROM flight_event_v2_packet WHERE packet_id = ? FOR UPDATE", (rs, n) -> packet(rs), packetId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public ChainRow findChain(String chainId) {
        var rows = jdbc.query(chainSql() + " WHERE chain_id = ? FOR UPDATE", (rs, n) -> chain(rs), chainId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public ChainRow findBinding(ParsedPacket packet) {
        var rows = jdbc.query(chainSql() + " WHERE order_id = ? AND recording_source_id = ? "
                        + "AND serial_number = ? AND serial_source = ? AND model_code = ? FOR UPDATE",
                (rs, n) -> chain(rs), packet.orderId(), packet.recordingSourceId(),
                utf8(packet.serialNumber()), utf8(packet.serialSource()), utf8(packet.modelCode()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public String packetAtHeight(String chainId, long height) {
        var rows = jdbc.queryForList("SELECT packet_id FROM flight_event_v2_packet WHERE chain_id = ? AND height = ? FOR UPDATE",
                String.class, chainId, height);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insertChain(ParsedPacket packet, long now) {
        jdbc.update("INSERT INTO flight_event_v2_chain (chain_id, order_id, recording_source_id, "
                        + "serial_number, serial_source, model_code, schema_version, hash_version, anchor_hash, contiguous_height, "
                        + "contiguous_hash, highest_received_height, integrity, validation_version, projection_revision, created_at_ms) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0,NULL,0,'OPEN',0,0,?)",
                packet.chainId(), packet.orderId(), packet.recordingSourceId(),
                utf8(packet.serialNumber()), utf8(packet.serialSource()), utf8(packet.modelCode()),
                packet.schemaVersion(), packet.hashVersion(), packet.anchor(), now);
    }

    public void deleteChainIfEmpty(String chainId) {
        Integer packets = jdbc.queryForObject("SELECT COUNT(*) FROM flight_event_v2_packet WHERE chain_id = ?", Integer.class, chainId);
        if (packets == null || packets == 0) jdbc.update("DELETE FROM flight_event_v2_chain WHERE chain_id = ?", chainId);
    }

    public void insertPacket(long uploaderPilotId, ParsedPacket packet, byte[] raw, long decidedAt) {
        jdbc.update("INSERT INTO flight_event_v2_packet (packet_id, chain_id, order_id, uploader_pilot_id, height, prev_packet_id, prev_chain_hash, "
                        + "chain_hash, content_digest, raw_sha256, raw_bytes, canonical_utf8, decided_at_ms) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                packet.packetId(), packet.chainId(), packet.orderId(), uploaderPilotId, packet.height(), packet.prevPacketId(),
                packet.prevChainHash(), packet.chainHash(), packet.contentDigest(), EventChainHasher.sha256(raw),
                raw, packet.canonicalUtf8(), decidedAt);
    }

    public void insertReceipt(ParsedPacket packet, long decidedAt) {
        jdbc.update("INSERT INTO flight_event_v2_receipt (packet_id, receipt_version, content_digest, chain_hash, durability, decided_at_ms) "
                        + "VALUES (?,?,?,?,?,?)",
                packet.packetId(), EventLimits.RECEIPT_VERSION, packet.contentDigest(), packet.chainHash(),
                EventLimits.DURABILITY, decidedAt);
    }

    public void insertResult(String packetId, ParsedEvent event, EventDecider.Decision decision) {
        jdbc.update("INSERT INTO flight_event_v2_result (packet_id, event_ordinal, event_id, outcome, reason, source_packet_id) "
                        + "VALUES (?,?,?,?,?,?)",
                packetId, event.ordinal(), event.eventId(), decision.outcome(), decision.reason(), decision.sourcePacketId());
    }

    public void insertFact(ParsedPacket packet, ParsedEvent event) {
        jdbc.update("INSERT INTO flight_event_v2_fact (event_id, packet_id, chain_id, order_id, recording_source_id, "
                        + "serial_number, serial_source, model_code, event_type, captured_at_ms, payload_canonical) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                event.eventId(), packet.packetId(), packet.chainId(), packet.orderId(),
                packet.recordingSourceId(), utf8(packet.serialNumber()), utf8(packet.serialSource()), utf8(packet.modelCode()),
                event.type(), event.capturedAt(), event.payloadCanonical());
    }

    public FactRow findFact(String eventId) {
        var rows = jdbc.query("SELECT event_id, packet_id, order_id, recording_source_id, chain_id, "
                + "serial_number, serial_source, model_code, event_type, captured_at_ms, payload_canonical "
                + "FROM flight_event_v2_fact WHERE event_id = ? FOR UPDATE", (rs, n) -> new FactRow(rs.getString("event_id"),
                rs.getString("packet_id"), new EventAttribution(rs.getString("order_id"),
                rs.getString("recording_source_id"), rs.getString("chain_id"), utf8(rs.getBytes("serial_number")),
                utf8(rs.getBytes("serial_source")), utf8(rs.getBytes("model_code")), rs.getString("event_type"),
                rs.getLong("captured_at_ms"), rs.getBytes("payload_canonical"))), eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insertConflict(String kind, String orderId, String chainId, String packetId, byte[] raw, String detail) {
        jdbc.update("INSERT INTO flight_event_v2_conflict (kind, order_id, chain_id, packet_id, raw_sha256, raw_bytes, detail, created_at_ms) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                kind, orderId, chainId, packetId, EventChainHasher.sha256(raw), raw, detail, System.currentTimeMillis());
    }

    public List<ResultRow> results(String packetId) {
        return jdbc.query("SELECT event_ordinal, event_id, outcome, reason, source_packet_id FROM flight_event_v2_result "
                + "WHERE packet_id = ? ORDER BY event_ordinal", (rs, n) -> new ResultRow(rs.getInt("event_ordinal"),
                rs.getString("event_id"), rs.getString("outcome"), rs.getString("reason"), rs.getString("source_packet_id")), packetId);
    }

    public ReceiptRow receipt(String packetId) {
        var rows = jdbc.query("SELECT receipt_version, content_digest, chain_hash, durability, decided_at_ms "
                + "FROM flight_event_v2_receipt WHERE packet_id = ?", (rs, n) -> new ReceiptRow(rs.getInt("receipt_version"),
                rs.getBytes("content_digest"), rs.getBytes("chain_hash"), rs.getString("durability"), rs.getLong("decided_at_ms")), packetId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Long> heightsBetween(String chainId, long fromHeight, long toHeight) {
        return jdbc.queryForList("SELECT height FROM flight_event_v2_packet WHERE chain_id = ? AND height >= ? AND height <= ? "
                + "ORDER BY height", Long.class, chainId, fromHeight, toHeight);
    }

    public boolean hasUploader(String chainId, long pilotId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM flight_event_v2_packet WHERE chain_id = ? AND uploader_pilot_id = ?",
                Integer.class, chainId, pilotId);
        return count != null && count > 0;
    }

    public long maxHeight(String chainId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(height), 0) FROM flight_event_v2_packet WHERE chain_id = ?",
                Long.class, chainId);
        return value == null ? 0 : value;
    }

    public List<EventChainRepair.Link> linksAfter(String chainId, long aboveHeight, int limit) {
        return jdbc.query("SELECT height, packet_id, prev_packet_id, prev_chain_hash, chain_hash FROM flight_event_v2_packet "
                        + "WHERE chain_id = ? AND height > ? ORDER BY height LIMIT ?",
                (rs, n) -> new EventChainRepair.Link(rs.getLong("height"), rs.getString("packet_id"),
                        rs.getString("prev_packet_id"), rs.getBytes("prev_chain_hash"), rs.getBytes("chain_hash")),
                chainId, aboveHeight, limit);
    }

    public String packetIdAt(String chainId, long height) {
        var rows = jdbc.queryForList("SELECT packet_id FROM flight_event_v2_packet WHERE chain_id = ? AND height = ?",
                String.class, chainId, height);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void updateProgress(String chainId, long contiguous, byte[] contiguousHash, long highest, String integrity,
            long version, long projectionRevision) {
        jdbc.update("UPDATE flight_event_v2_chain SET contiguous_height = ?, contiguous_hash = ?, highest_received_height = ?, "
                + "integrity = ?, validation_version = ?, projection_revision = ? WHERE chain_id = ?",
                ps -> {
                    ps.setLong(1, contiguous);
                    if (contiguousHash == null) ps.setNull(2, Types.BINARY);
                    else ps.setBytes(2, contiguousHash);
                    ps.setLong(3, highest);
                    ps.setString(4, integrity);
                    ps.setLong(5, version);
                    ps.setLong(6, projectionRevision);
                    ps.setString(7, chainId);
                });
    }

    public List<HeightHash> hashes(String chainId, long from, long to) {
        return jdbc.query("SELECT height, packet_id, chain_hash, content_digest FROM flight_event_v2_packet "
                        + "WHERE chain_id = ? AND height BETWEEN ? AND ? ORDER BY height",
                (rs, n) -> new HeightHash(rs.getLong("height"), rs.getString("packet_id"), rs.getBytes("chain_hash"),
                        rs.getBytes("content_digest")), chainId, from, to);
    }

    private static String chainSql() {
        return "SELECT chain_id, order_id, recording_source_id, serial_number, serial_source, "
                + "model_code, schema_version, hash_version, anchor_hash, contiguous_height, contiguous_hash, "
                + "highest_received_height, integrity, validation_version, projection_revision FROM flight_event_v2_chain";
    }

    private static PacketRow packet(ResultSet rs) throws SQLException {
        return new PacketRow(rs.getString("packet_id"), rs.getString("chain_id"), rs.getString("order_id"), rs.getLong("uploader_pilot_id"),
                rs.getLong("height"), rs.getBytes("content_digest"), rs.getBytes("chain_hash"), rs.getLong("decided_at_ms"));
    }

    private static ChainRow chain(ResultSet rs) throws SQLException {
        return new ChainRow(rs.getString("chain_id"), rs.getString("order_id"),
                rs.getString("recording_source_id"), utf8(rs.getBytes("serial_number")),
                utf8(rs.getBytes("serial_source")), utf8(rs.getBytes("model_code")), rs.getInt("schema_version"),
                rs.getInt("hash_version"), rs.getBytes("anchor_hash"), rs.getLong("contiguous_height"),
                rs.getBytes("contiguous_hash"), rs.getLong("highest_received_height"), rs.getString("integrity"),
                rs.getLong("validation_version"), rs.getLong("projection_revision"));
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String utf8(byte[] raw) { return raw == null ? null : new String(raw, StandardCharsets.UTF_8); }

    public record PacketRow(String packetId, String chainId, String orderId, long uploaderPilotId, long height, byte[] contentDigest,
            byte[] chainHash, long decidedAt) { }
    public record ReceiptRow(int receiptVersion, byte[] contentDigest, byte[] chainHash, String durability, long decidedAt) { }
    public record ResultRow(int ordinal, String eventId, String outcome, String reason, String sourcePacketId) { }
    public record FactRow(String eventId, String packetId, EventAttribution attribution) { }
    public record HeightHash(long height, String packetId, byte[] chainHash, byte[] contentDigest) { }
    public record ChainRow(String chainId, String orderId, String recordingSourceId,
            String serialNumber, String serialSource, String modelCode, int schemaVersion, int hashVersion, byte[] anchor,
            long contiguousHeight, byte[] contiguousHash, long highestReceivedHeight, String integrity,
            long validationVersion, long projectionRevision) {
        public boolean sameIdentity(ParsedPacket packet) {
            return orderId.equals(packet.orderId())
                    && recordingSourceId.equals(packet.recordingSourceId()) && serialNumber.equals(packet.serialNumber())
                    && serialSource.equals(packet.serialSource()) && modelCode.equals(packet.modelCode())
                    && schemaVersion == packet.schemaVersion() && hashVersion == packet.hashVersion()
                    && MessageDigest.isEqual(anchor, packet.anchor());
        }
    }
}

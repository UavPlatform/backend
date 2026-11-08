package com.uav.flight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.flight.event.EventChainLock;
import com.uav.flight.pojo.vo.FlightEventVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** REQ-PILOT-EXEC-007 / ADR-0014. Facts and their read projection commit atomically. */
@Service
public class EventProjectionWriter {
    private final JdbcTemplate jdbc;
    private final EventChainLock locks;
    private final ObjectMapper json = new ObjectMapper();

    public EventProjectionWriter(JdbcTemplate jdbc, EventChainLock locks) {
        this.jdbc = jdbc; this.locks = locks;
    }

    public long projectPacket(String orderId, String packetId) {
        copy("f.order_id = ? AND f.packet_id = ?", orderId, packetId);
        return revision(orderId);
    }

    /** Events this packet contributed to the projection; empty for a replayed packet. */
    public List<FlightEventVO> projectedBy(String packetId) {
        return jdbc.query("SELECT " + FlightEventRows.columns("p") + " FROM flight_projection_event p "
                        + "JOIN flight_event_v2_fact f ON f.event_id = p.event_id WHERE f.packet_id = ? "
                        + "ORDER BY p.change_sequence", (rs, n) -> FlightEventRows.map(rs, json), packetId);
    }

    /** Internal maintenance entry point. Reproduces stable change sequences from immutable facts. */
    @Transactional
    public long rebuild(String orderId) {
        locks.lock(orderId);
        jdbc.update("DELETE FROM flight_projection_event WHERE order_id = ?", orderId);
        copy("f.order_id = ?", orderId);
        long revision = revision(orderId);
        jdbc.update("UPDATE flight_event_v2_chain SET projection_revision = ? WHERE order_id = ?", revision, orderId);
        return revision;
    }

    public long revision(String orderId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(change_sequence),0) FROM flight_projection_event WHERE order_id = ?",
                Long.class, orderId);
        return value == null ? 0 : value;
    }

    private void copy(String predicate, Object... args) {
        jdbc.update("INSERT INTO flight_projection_event (change_sequence,event_id,order_id,serial_number,serial_source,"
                + "model_code,event_type,captured_at_ms,received_at_ms,payload_canonical) "
                + "SELECT f.fact_sequence,f.event_id,f.order_id,f.serial_number,f.serial_source,f.model_code,"
                + "f.event_type,f.captured_at_ms,p.decided_at_ms,f.payload_canonical "
                + "FROM flight_event_v2_fact f JOIN flight_event_v2_packet p ON p.packet_id=f.packet_id WHERE " + predicate
                + " AND NOT EXISTS (SELECT 1 FROM flight_projection_event x WHERE x.event_id=f.event_id)", args);
    }
}

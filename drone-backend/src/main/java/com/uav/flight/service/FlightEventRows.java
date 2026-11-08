package com.uav.flight.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.flight.pojo.vo.FlightEventVO;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** One mapping for every read of {@code flight_projection_event}; column order is part of it. */
final class FlightEventRows {
    /** Column list for a single-table read; pass an alias when the query joins another table. */
    static String columns(String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return prefix + "change_sequence," + prefix + "event_id," + prefix + "serial_number," + prefix + "serial_source,"
                + prefix + "model_code," + prefix + "event_type," + prefix + "captured_at_ms," + prefix + "received_at_ms,"
                + prefix + "payload_canonical";
    }

    private FlightEventRows() { }

    static FlightEventVO map(ResultSet rs, ObjectMapper json) throws SQLException {
        return new FlightEventVO(rs.getLong(1), rs.getString(2), utf8(rs.getBytes(3)), utf8(rs.getBytes(4)),
                utf8(rs.getBytes(5)), rs.getString(6), rs.getLong(7), rs.getLong(8), payload(json, rs.getBytes(9)));
    }

    static Map<String, Object> payload(ObjectMapper json, byte[] bytes) {
        try {
            return json.readValue(bytes, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("accepted event payload is not readable", e);
        }
    }

    private static String utf8(byte[] raw) { return new String(raw, StandardCharsets.UTF_8); }
}

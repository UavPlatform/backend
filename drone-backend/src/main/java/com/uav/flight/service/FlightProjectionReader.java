package com.uav.flight.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.flight.pojo.vo.FlightEventPageVO;
import com.uav.flight.pojo.vo.FlightEventVO;
import com.uav.flight.pojo.vo.FlightProjectionVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** All flight reads are deterministic views of accepted immutable events. */
@Service
public class FlightProjectionReader {
    private static final long FRESH_MS = 30_000;
    private final JdbcTemplate jdbc;
    private final DispatchOrderRepository orders;
    private final ObjectMapper json;

    public FlightProjectionReader(JdbcTemplate jdbc, DispatchOrderRepository orders) {
        this.jdbc = jdbc; this.orders = orders; this.json = new ObjectMapper();
    }

    @Transactional(readOnly = true)
    public FlightProjectionVO current(Long caller, Integer role, String orderId) {
        readable(caller, role, orderId);
        long revision = revision(orderId);
        var all = jdbc.query("SELECT change_sequence,event_id,serial_number,serial_source,model_code,event_type,"
                + "captured_at_ms,received_at_ms,payload_canonical FROM flight_projection_event WHERE order_id=? "
                + "ORDER BY captured_at_ms,event_id", (rs, n) -> new FlightEventVO(rs.getLong(1),rs.getString(2),
                new String(rs.getBytes(3),StandardCharsets.UTF_8),new String(rs.getBytes(4),StandardCharsets.UTF_8),
                new String(rs.getBytes(5),StandardCharsets.UTF_8),rs.getString(6),rs.getLong(7),rs.getLong(8),
                payload(rs.getBytes(9))), orderId);
        long now = System.currentTimeMillis();
        var latestByAircraft = new LinkedHashMap<String, Map<String, FlightEventVO>>();
        var identity = new LinkedHashMap<String, FlightEventVO>();
        FlightEventVO lastPosition = null;
        for (var event : all) {
            String key = key(event.serialNumber(), event.serialSource());
            var latest = latestByAircraft.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            var previous = latest.get(event.type());
            if (previous == null || compare(event, previous) > 0) latest.put(event.type(), event);
            identity.put(key, event);
            if ("POSITION".equals(event.type()) && (lastPosition == null || compare(event, lastPosition) > 0)) lastPosition = event;
        }
        var aircraft = new ArrayList<FlightProjectionVO.Aircraft>();
        for (var entry : latestByAircraft.entrySet()) {
            var id = identity.get(entry.getKey());
            var newest = entry.getValue().values().stream().max(FlightProjectionReader::compare).orElse(null);
            aircraft.add(new FlightProjectionVO.Aircraft(id.serialNumber(), id.serialSource(), id.modelCode(),
                    Map.copyOf(entry.getValue()), stale(newest, now)));
        }
        aircraft.sort(Comparator.comparing(FlightProjectionVO.Aircraft::serialNumber).thenComparing(FlightProjectionVO.Aircraft::serialSource));
        return new FlightProjectionVO(revision, List.copyOf(aircraft), lastPosition,
                stale(lastPosition, now), FlightPeriods.from(all));
    }

    @Transactional(readOnly = true)
    public FlightEventPageVO track(Long caller, Integer role, String orderId, long after, int limit) {
        readable(caller, role, orderId); validate(after, limit);
        var rows = pageByChange(orderId, after, limit);
        var items = rows.stream().limit(limit).toList();
        return new FlightEventPageVO(items, revision(orderId), items.isEmpty() ? after : items.getLast().changeSequence(),
                null, null, rows.size() > limit);
    }

    @Transactional(readOnly = true)
    public FlightEventPageVO history(Long caller, Integer role, String orderId, String serial, String source,
            Long afterAt, String afterId, int limit) {
        readable(caller, role, orderId); validate(0, limit);
        if ((serial == null) != (source == null) || (afterAt == null) != (afterId == null))
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        String filter = serial == null ? "" : " AND serial_number=? AND serial_source=?";
        String cursor = afterAt == null ? "" : " AND (captured_at_ms>? OR (captured_at_ms=? AND event_id>?))";
        var args = new ArrayList<Object>(); args.add(orderId);
        if (serial != null) { args.add(serial.getBytes(StandardCharsets.UTF_8)); args.add(source.getBytes(StandardCharsets.UTF_8)); }
        if (afterAt != null) { args.add(afterAt); args.add(afterAt); args.add(afterId); }
        args.add(limit + 1);
        var rows = jdbc.query("SELECT change_sequence,event_id,serial_number,serial_source,model_code,event_type,captured_at_ms,"
                + "received_at_ms,payload_canonical FROM flight_projection_event WHERE order_id=?" + filter + cursor
                + " ORDER BY captured_at_ms,event_id LIMIT ?", (rs,n) -> row(rs), args.toArray());
        var items = rows.stream().limit(limit).toList(); var last = items.isEmpty() ? null : items.getLast();
        return new FlightEventPageVO(items, revision(orderId), items.isEmpty()?0:items.getLast().changeSequence(),
                last==null?null:last.capturedAt(), last==null?null:last.eventId(), rows.size()>limit);
    }

    private List<FlightEventVO> pageByChange(String id, long after, int limit) {
        return jdbc.query("SELECT change_sequence,event_id,serial_number,serial_source,model_code,event_type,captured_at_ms,"
                + "received_at_ms,payload_canonical FROM flight_projection_event WHERE order_id=? AND event_type='POSITION' AND change_sequence>? "
                + "ORDER BY change_sequence LIMIT ?", (rs,n)->row(rs), id, after, limit+1);
    }
    private FlightEventVO row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FlightEventVO(rs.getLong(1),rs.getString(2),new String(rs.getBytes(3),StandardCharsets.UTF_8),
                new String(rs.getBytes(4),StandardCharsets.UTF_8),new String(rs.getBytes(5),StandardCharsets.UTF_8),
                rs.getString(6),rs.getLong(7),rs.getLong(8),payload(rs.getBytes(9)));
    }
    private Map<String,Object> payload(byte[] bytes) {
        try { return json.readValue(bytes, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("accepted event payload is not readable", e); }
    }
    private long revision(String orderId) {
        Long value=jdbc.queryForObject("SELECT COALESCE(MAX(change_sequence),0) FROM flight_projection_event WHERE order_id=?",Long.class,orderId);
        return value==null?0:value;
    }
    private boolean stale(FlightEventVO e,long now) { return e==null || now-e.receivedAt()>FRESH_MS || now-e.capturedAt()>FRESH_MS || e.capturedAt()>now; }
    private static int compare(FlightEventVO a,FlightEventVO b) { int time=Long.compare(a.capturedAt(),b.capturedAt());return time!=0?time:a.eventId().compareTo(b.eventId()); }
    private static String key(String serial,String source) { return serial+"\u0000"+source; }
    private void readable(Long caller,Integer role,String id) {
        var order=orders.findById(id).orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,ApiErrorCode.ORDER_NOT_FOUND));
        if(!Objects.equals(role,2)&&!Objects.equals(caller,order.getUserId())&&!Objects.equals(caller,order.getAssignedPilotId()))
            throw new BusinessException(HttpStatus.NOT_FOUND,ApiErrorCode.ORDER_NOT_FOUND);
    }
    private static void validate(long after,int limit) { if(after<0||limit<1||limit>1000)throw new BusinessException(ApiErrorCode.INVALID_PARAM); }
}

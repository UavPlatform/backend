package com.uav.flight.service;

import com.alibaba.fastjson.JSON;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.mapper.*;
import com.uav.flight.pojo.dto.*;
import com.uav.flight.pojo.entity.*;
import com.uav.flight.pojo.vo.*;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** REQ-PILOT-EXEC-007 / REQ-SHIP-EXEC-004 / ADR-0009. Order-scoped real aircraft records. */
@Service
public class FlightRecordService {
    private final DispatchOrderRepository orders;
    private final FlightConnectionRepository connections;
    private final FlightSampleRepository samples;
    private final AircraftModelRepository models;
    private final DispatchLiveService live;
    private final FlightTelemetryBroadcaster broadcaster;
    private static final long BACKFILL_SKEW_MS = 10 * 60_000L;
    private static final int HISTORY_MAX = 1000;
    public FlightRecordService(DispatchOrderRepository orders, FlightConnectionRepository connections,
            FlightSampleRepository samples, AircraftModelRepository models, DispatchLiveService live,
            FlightTelemetryBroadcaster broadcaster) {
        this.orders = orders; this.connections = connections; this.samples = samples; this.models = models; this.live = live;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public FlightConnectionVO connect(Long pilot, String id, FlightConnectDTO input) {
        var order = locked(id);
        requirePilot(order, pilot, input.assignmentVersion());
        var old = connections.findByPilotIdAndRequestKey(pilot, input.requestKey());
        if (old.isPresent()) {
            var c = old.get();
            if (!id.equals(c.getOrderId()) || c.getAssignmentVersion() != input.assignmentVersion()
                    || !input.serialNumber().equals(c.getSerialNumber()) || !input.modelCode().equals(c.getModelCode())
                    || !input.modelName().equals(c.getModelName())) throw conflict();
            return view(c, order);
        }
        // ADR-0009 offline-first: a connection recorded locally while offline may be registered after delivery was
        // submitted (same pilot and assignment, client start time required); it is stored as already ended so its
        // cached samples can be backfilled, and it never replaces live connections or takes the device.
        boolean late = !OPEN_STAGES.contains(order.getStage());
        if (late && (!BACKFILL_STAGES.contains(order.getStage()) || input.startedAt() == null)) throw conflict();
        if (List.of(input.serialNumber(), input.modelCode(), input.modelName()).stream().anyMatch(this::unknown))
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        if (late) return lateConnection(pilot, order, input);
        closeOrder(id, "REPLACED");
        var c = newConnection(pilot, id, input);
        c.setOpenedAt(openedAt(input.startedAt())); c.setActiveOrderId(id); c.setActiveSerial(input.serialNumber().trim().toUpperCase(Locale.ROOT));
        try { connections.saveAndFlush(c); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw conflict(); }
        // ADR-0007 #6/#9: the order's drone is what djifly reported, never a manually entered number.
        order.setExecutedSerialNumber(c.getSerialNumber()); order.setExecutedModelCode(c.getModelCode());
        order.setExecutedModelName(c.getModelName()); order.setExecutedAircraftModelId(c.getAircraftModelId());
        orders.save(order);
        return view(c, order);
    }

    private FlightConnectionVO lateConnection(Long pilot, DispatchOrder order, FlightConnectDTO input) {
        LocalDateTime end = order.getDeliveredAt() != null ? order.getDeliveredAt() : LocalDateTime.now();
        LocalDateTime opened = openedAt(input.startedAt());
        if (opened.isAfter(end)) throw conflict();
        var c = newConnection(pilot, order.getId(), input);
        c.setOpenedAt(opened); c.setEndedAt(end); c.setEndReason("LATE_REGISTRATION");
        try { connections.saveAndFlush(c); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw conflict(); }
        if (order.getExecutedSerialNumber() == null) {
            order.setExecutedSerialNumber(c.getSerialNumber()); order.setExecutedModelCode(c.getModelCode());
            order.setExecutedModelName(c.getModelName()); order.setExecutedAircraftModelId(c.getAircraftModelId());
            orders.save(order);
        }
        return view(c, order);
    }

    private FlightConnection newConnection(Long pilot, String id, FlightConnectDTO input) {
        var c = new FlightConnection();
        c.setId(UUID.randomUUID().toString()); c.setOrderId(id); c.setPilotId(pilot);
        c.setAssignmentVersion(input.assignmentVersion()); c.setRequestKey(input.requestKey());
        c.setSerialNumber(input.serialNumber()); c.setModelCode(input.modelCode()); c.setModelName(input.modelName());
        var matches = models.findAll().stream().filter(m -> m.getModelCode().equalsIgnoreCase(input.modelCode())
                || m.getDisplayName().equalsIgnoreCase(input.modelName())).toList();
        if (matches.size() == 1) c.setAircraftModelId(matches.getFirst().getId());
        return c;
    }

    @Transactional
    public FlightSampleVO sample(Long pilot, String id, String connectionId, FlightSampleDTO input) {
        var order = locked(id);
        var c = connection(id, connectionId);
        requirePilot(order, pilot, c.getAssignmentVersion());
        requireOpenStage(order);
        if (c.getEndedAt() != null || !pilot.equals(c.getPilotId())) throw conflict();
        validate(input);
        var stored = store(c, input);
        if (stored.created()) advance(c, stored.sample());
        return view(stored.sample());
    }

    /**
     * Periodic or offline backfill upload (ADR-0009). Accepted while the pilot still holds the assignment, including
     * after the connection ended or delivery was submitted, so cached samples are not lost. Already stored samples
     * (same connection+sequence or device+capture time, also concurrent inserts) are skipped and counted as duplicates;
     * invalid or out-of-window items are counted as rejected. Neither fails the batch.
     */
    @Transactional
    public FlightSampleBatchVO batch(Long pilot, String id, String connectionId, FlightSampleBatchDTO input) {
        var order = locked(id);
        var c = connection(id, connectionId);
        requirePilot(order, pilot, c.getAssignmentVersion());
        if (!pilot.equals(c.getPilotId()) || !BACKFILL_STAGES.contains(order.getStage())) throw conflict();
        long earliest = c.getOpenedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - BACKFILL_SKEW_MS;
        long latest = (c.getEndedAt() == null ? System.currentTimeMillis()
                : c.getEndedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) + BACKFILL_SKEW_MS;
        int accepted = 0, duplicates = 0, rejected = 0;
        FlightSampleDTO newest = null;
        for (var item : input.samples().stream().sorted(Comparator.comparingLong(FlightSampleDTO::sequence)).toList()) {
            if (!valid(item) || item.capturedAt() < earliest || item.capturedAt() > latest) { rejected++; continue; }
            // Already stored (same connection+sequence or same device+capture time): skip, first write wins.
            if (samples.findByConnectionIdAndSequenceNumber(c.getId(), item.sequence()).isPresent()
                    || samples.findBySerialNumberAndCapturedAt(c.getSerialNumber(), item.capturedAt()).isPresent()) {
                duplicates++; continue;
            }
            int rows = samples.insertIgnore(c.getId(), c.getOrderId(), c.getSerialNumber(), item.sequence(), fingerprint(item),
                    item.capturedAt(), LocalDateTime.now(), item.latitude(), item.longitude(), item.altitude(), item.speed(),
                    item.battery(), item.flying(), item.payloadKg());
            if (rows == 0) { duplicates++; continue; }
            accepted++;
            if (newest == null || item.sequence() > newest.sequence()) newest = item;
        }
        if (newest != null && c.getEndedAt() == null) {
            samples.findBySerialNumberAndCapturedAt(c.getSerialNumber(), newest.capturedAt()).ifPresent(s -> advance(c, s));
        }
        return new FlightSampleBatchVO(accepted, duplicates, rejected);
    }

    private record StoredSample(FlightSample sample, boolean created) { }

    /** Idempotent by (connection, sequence) and by (device serial, capture time); same key + other content = 409. */
    private StoredSample store(FlightConnection c, FlightSampleDTO input) {
        String fingerprint = fingerprint(input);
        var retry = samples.findByConnectionIdAndSequenceNumber(c.getId(), input.sequence())
                .or(() -> samples.findBySerialNumberAndCapturedAt(c.getSerialNumber(), input.capturedAt()));
        if (retry.isPresent()) {
            if (!fingerprint.equals(retry.get().getFingerprint())) throw conflict();
            return new StoredSample(retry.get(), false);
        }
        var s = new FlightSample();
        s.setConnectionId(c.getId()); s.setOrderId(c.getOrderId()); s.setSerialNumber(c.getSerialNumber());
        s.setSequenceNumber(input.sequence());
        s.setCapturedAt(input.capturedAt()); s.setReceivedAt(LocalDateTime.now()); s.setFingerprint(fingerprint);
        s.setLatitude(input.latitude()); s.setLongitude(input.longitude()); s.setAltitude(input.altitude());
        s.setSpeed(input.speed()); s.setBattery(input.battery()); s.setFlying(input.flying()); s.setPayloadKg(input.payloadKg());
        samples.saveAndFlush(s);
        return new StoredSample(s, true);
    }

    private void advance(FlightConnection c, FlightSample s) {
        var latest = c.getLatestSampleId() == null ? null : samples.findById(c.getLatestSampleId()).orElse(null);
        if (latest == null || s.getSequenceNumber() > latest.getSequenceNumber()) {
            c.setLatestSampleId(s.getId());
            connections.save(c);
            broadcaster.publish(c, view(s));
        }
    }

    private static String fingerprint(FlightSampleDTO input) {
        // Canonical scalar list preserves nulls; never serialize records using fastjson's bean codec.
        var values = new ArrayList<Object>(Arrays.asList(input.capturedAt(), input.latitude(), input.longitude(),
                input.altitude(), input.speed(), input.battery(), input.flying()));
        if (input.payloadKg() != null) values.add(input.payloadKg());
        return sha256(JSON.toJSONString(values).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Transactional
    public void close(Long pilot, String id, String connectionId) {
        locked(id);
        var c = connection(id, connectionId);
        if (!pilot.equals(c.getPilotId())) throw denied();
        end(c, "DISCONNECTED");
    }

    /** Caller holds the order lock; business completion never controls physical flight. */
    @Transactional
    public void closeOrder(String id, String reason) {
        connections.findByActiveOrderId(id).ifPresent(c -> end(c, reason));
        connections.flush();
    }

    @Transactional(readOnly = true)
    public FlightViewVO current(Long caller, Integer role, String id) {
        var order = readable(caller, role, id);
        var c = connections.findTopByOrderIdOrderByOpenedAtDescIdDesc(id).orElse(null);
        var latest = c == null || c.getLatestSampleId() == null ? null : samples.findById(c.getLatestSampleId()).orElse(null);
        var point = samples.lastPositions(id, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        long now = System.currentTimeMillis();
        boolean stale = c == null || c.getEndedAt() != null || latest == null
                || latest.getReceivedAt().isBefore(LocalDateTime.now().minusSeconds(30))
                // Delayed uploads remain historical samples, never fresh measurements.
                || latest.getCapturedAt() < now - 30_000
                || latest.getCapturedAt() > now;
        return new FlightViewVO(c == null ? null : view(c, order), latest == null ? null : view(latest), point == null ? null : view(point), stale,
                connections.findByOrderIdOrderByOpenedAtAscIdAsc(id).stream().map(item -> view(item, order)).toList());
    }

    @Transactional(readOnly = true)
    public FlightTrackVO track(Long caller, Integer role, String id, long after) {
        readable(caller, role, id);
        var page = samples.findByOrderIdAndIdGreaterThanOrderByIdAsc(id, after, PageRequest.of(0, 200));
        var items = page.getContent().stream().map(this::view).toList();
        return new FlightTrackVO(items, items.isEmpty() ? after : items.getLast().sampleId(), page.hasNext());
    }

    /** Replay / history query ordered by capture time, optionally limited to [from, to] (epoch millis). */
    @Transactional(readOnly = true)
    public FlightHistoryVO history(Long caller, Integer role, String id, Long from, Long to,
            Long afterCapturedAt, Long afterSampleId, int limit) {
        var order = readable(caller, role, id);
        long lo = from == null ? 0 : from, hi = to == null ? Long.MAX_VALUE : to;
        if (lo > hi || limit < 1 || limit > HISTORY_MAX) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        long afterAt = afterCapturedAt == null ? Long.MIN_VALUE : afterCapturedAt;
        long afterId = afterSampleId == null ? Long.MIN_VALUE : afterSampleId;
        var page = samples.history(id, lo, hi, afterAt, afterId, PageRequest.of(0, limit + 1));
        boolean more = page.size() > limit;
        var items = page.stream().limit(limit).map(this::view).toList();
        var last = items.isEmpty() ? null : items.getLast();
        return new FlightHistoryVO(items, connections.findByOrderIdOrderByOpenedAtAscIdAsc(id).stream().map(c -> view(c, order)).toList(),
                last == null ? null : last.capturedAt(), last == null ? null : last.sampleId(), more);
    }

    /** Offline-first connections report their local start; clamp to [now - 24h, now] so a skewed clock cannot widen the window much. */
    static LocalDateTime openedAt(Long startedAt) {
        long now = System.currentTimeMillis();
        if (startedAt == null) return LocalDateTime.now();
        long at = Math.max(now - 86_400_000L, Math.min(now, startedAt));
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault());
    }

    private void end(FlightConnection c, String reason) {
        if (c.getEndedAt() != null) return;
        live.closeConnection(c.getId());
        c.setEndedAt(LocalDateTime.now()); c.setEndReason(reason); c.setActiveOrderId(null); c.setActiveSerial(null);
        connections.save(c);
    }
    private FlightConnection connection(String id, String cid) {
        return connections.findById(cid).filter(c -> id.equals(c.getOrderId())).orElseThrow(this::missing);
    }
    private DispatchOrder locked(String id) { return orders.lockOrder(id).orElseThrow(this::missing); }
    private DispatchOrder readable(Long caller, Integer role, String id) {
        var order = orders.findById(id).orElseThrow(this::missing);
        if (!Objects.equals(role, 2) && !Objects.equals(caller, order.getUserId())
                && !Objects.equals(caller, order.getAssignedPilotId())) throw missing();
        return order;
    }
    private void requirePilot(DispatchOrder o, Long pilot, long version) {
        if (!Objects.equals(pilot, o.getAssignedPilotId()) || version != o.getAssignmentVersion()) throw conflict();
    }
    private static final Set<String> BACKFILL_STAGES = Set.of("PILOT_ARRIVED", "IN_TRANSIT", "DELIVERY_CONFIRMATION", "COMPLETED");
    private static final Set<String> OPEN_STAGES = Set.of("PILOT_ARRIVED", "IN_TRANSIT");
    private void requireOpenStage(DispatchOrder o) {
        if (!OPEN_STAGES.contains(o.getStage())) throw conflict();
    }
    private static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private boolean unknown(String value) {
        return value.isBlank() || Set.of("n/a", "unrecognized", "unknown", "unknown_aircraft", "未知设备").contains(value.trim().toLowerCase(Locale.ROOT));
    }
    private void validate(FlightSampleDTO d) {
        if (!valid(d)) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
    }
    private static boolean valid(FlightSampleDTO d) {
        return (d.latitude() == null) == (d.longitude() == null)
                && Arrays.asList(d.latitude(), d.longitude(), d.altitude(), d.speed(), d.payloadKg()).stream().allMatch(v -> v == null || Double.isFinite(v));
    }
    private FlightConnectionVO view(FlightConnection c, DispatchOrder order) {
        var model = c.getAircraftModelId() == null ? null : models.findById(c.getAircraftModelId()).orElse(null);
        BigDecimal payload = model == null ? null : model.getMaxPayloadKg();
        var fields = JSON.parseObject(order.getFieldsJson());
        var weight = fields.getBigDecimal("weightMax");
        if (weight == null) weight = fields.getBigDecimal("weightMin");
        return new FlightConnectionVO(c.getId(), c.getOrderId(), c.getAssignmentVersion(), c.getSerialNumber(), c.getModelCode(),
                c.getModelName(), c.getAircraftModelId(), payload, payload != null && weight != null && weight.compareTo(payload) > 0,
                c.getOpenedAt(), c.getEndedAt(), c.getEndReason());
    }
    private FlightSampleVO view(FlightSample s) {
        return new FlightSampleVO(s.getId(), s.getConnectionId(), s.getSequenceNumber(), s.getCapturedAt(), s.getReceivedAt(),
                s.getLatitude(), s.getLongitude(), s.getAltitude(), s.getSpeed(), s.getBattery(), s.getFlying(), s.getPayloadKg());
    }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND); }
    private BusinessException denied() { return new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION); }
    private BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.ORDER_STATUS_INVALID); }
}

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
    public FlightRecordService(DispatchOrderRepository orders, FlightConnectionRepository connections,
            FlightSampleRepository samples, AircraftModelRepository models, DispatchLiveService live) {
        this.orders = orders; this.connections = connections; this.samples = samples; this.models = models; this.live = live;
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
        requireOpenStage(order);
        if (List.of(input.serialNumber(), input.modelCode(), input.modelName()).stream().anyMatch(this::unknown))
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        closeOrder(id, "REPLACED");
        var c = new FlightConnection();
        c.setId(UUID.randomUUID().toString()); c.setOrderId(id); c.setPilotId(pilot);
        c.setAssignmentVersion(input.assignmentVersion()); c.setRequestKey(input.requestKey());
        c.setSerialNumber(input.serialNumber()); c.setModelCode(input.modelCode()); c.setModelName(input.modelName());
        var matches = models.findAll().stream().filter(m -> m.getModelCode().equalsIgnoreCase(input.modelCode())
                || m.getDisplayName().equalsIgnoreCase(input.modelName())).toList();
        if (matches.size() == 1) c.setAircraftModelId(matches.getFirst().getId());
        c.setOpenedAt(LocalDateTime.now()); c.setActiveOrderId(id); c.setActiveSerial(input.serialNumber().trim().toUpperCase(Locale.ROOT));
        try { connections.saveAndFlush(c); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw conflict(); }
        return view(c, order);
    }

    @Transactional
    public FlightSampleVO sample(Long pilot, String id, String connectionId, FlightSampleDTO input) {
        var order = locked(id);
        var c = connection(id, connectionId);
        requirePilot(order, pilot, c.getAssignmentVersion());
        requireOpenStage(order);
        if (c.getEndedAt() != null || !pilot.equals(c.getPilotId())) throw conflict();
        validate(input);
        // Canonical scalar list preserves nulls; never serialize records using fastjson's bean codec.
        String fingerprint = sha256(JSON.toJSONString(Arrays.asList(input.capturedAt(), input.latitude(), input.longitude(),
                input.altitude(), input.speed(), input.battery(), input.flying())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var retry = samples.findByConnectionIdAndSequenceNumber(connectionId, input.sequence());
        if (retry.isPresent()) {
            if (!fingerprint.equals(retry.get().getFingerprint())) throw conflict();
            return view(retry.get());
        }
        var s = new FlightSample();
        s.setConnectionId(connectionId); s.setOrderId(id); s.setSequenceNumber(input.sequence());
        s.setCapturedAt(input.capturedAt()); s.setReceivedAt(LocalDateTime.now()); s.setFingerprint(fingerprint);
        s.setLatitude(input.latitude()); s.setLongitude(input.longitude()); s.setAltitude(input.altitude());
        s.setSpeed(input.speed()); s.setBattery(input.battery()); s.setFlying(input.flying());
        samples.saveAndFlush(s);
        var latest = c.getLatestSampleId() == null ? null : samples.findById(c.getLatestSampleId()).orElse(null);
        if (latest == null || s.getSequenceNumber() > latest.getSequenceNumber()) c.setLatestSampleId(s.getId());
        connections.save(c);
        return view(s);
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
    private void requireOpenStage(DispatchOrder o) {
        if (!Set.of("PILOT_ARRIVED", "IN_TRANSIT").contains(o.getStage())) throw conflict();
    }
    private String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private boolean unknown(String value) {
        return value.isBlank() || Set.of("n/a", "unrecognized", "unknown", "unknown_aircraft", "未知设备").contains(value.trim().toLowerCase(Locale.ROOT));
    }
    private void validate(FlightSampleDTO d) {
        if ((d.latitude() == null) != (d.longitude() == null)
                || Arrays.asList(d.latitude(), d.longitude(), d.altitude(), d.speed()).stream().anyMatch(v -> v != null && !Double.isFinite(v)))
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
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
                s.getLatitude(), s.getLongitude(), s.getAltitude(), s.getSpeed(), s.getBattery(), s.getFlying());
    }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND); }
    private BusinessException denied() { return new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION); }
    private BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.ORDER_STATUS_INVALID); }
}

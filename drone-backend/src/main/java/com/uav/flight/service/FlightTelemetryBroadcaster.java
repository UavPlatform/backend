package com.uav.flight.service;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.flight.pojo.vo.FlightSampleVO;
import com.uav.live.pojo.dto.WsEnvelope;
import com.uav.server.ws.WsMessageNames;
import com.uav.server.ws.auth.WsAuthHandshakeInterceptor;
import com.uav.server.ws.service.WsMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web realtime push of persisted flight telemetry over {@code /ws/web} (REQ-SUP-MON-005/007, REQ-SHIP-EXEC-004).
 * Supervisors (role 2) without {@code orderId} receive every order; with {@code ?orderId=} a session receives one
 * order and must be its shipper or a supervisor. Only data already committed to the database is pushed.
 */
@Slf4j
@Service
public class FlightTelemetryBroadcaster {
    static final int SUPERVISOR = 2;
    private final DispatchOrderRepository orders;
    private final WsMessageService messages;
    private final Map<String, Set<WebSocketSession>> watchers = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> supervisors = ConcurrentHashMap.newKeySet();

    public FlightTelemetryBroadcaster(DispatchOrderRepository orders, WsMessageService messages) {
        this.orders = orders; this.messages = messages;
    }

    /** @return false when the caller may not watch the order; the handler then closes the session. */
    public boolean register(WebSocketSession session, String orderId) {
        Long user = asLong(session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID));
        Long role = asLong(session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_ROLE));
        boolean supervisor = role != null && role == SUPERVISOR;
        if (orderId == null || orderId.isBlank()) {
            if (supervisor) supervisors.add(session);
            return true;
        }
        boolean allowed = supervisor || orders.findById(orderId).map(o -> Objects.equals(o.getUserId(), user)).orElse(false);
        if (allowed) watchers.computeIfAbsent(orderId, k -> ConcurrentHashMap.newKeySet()).add(session);
        return allowed;
    }

    public void unregister(WebSocketSession session) {
        supervisors.remove(session);
        watchers.values().forEach(set -> set.remove(session));
        watchers.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /** Sends after the surrounding transaction commits (immediately when there is none). */
    public void publish(FlightConnection connection, FlightSampleVO sample) {
        WsEnvelope envelope = envelope(connection, sample);
        Runnable send = () -> deliver(connection.getOrderId(), envelope);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    static WsEnvelope envelope(FlightConnection c, FlightSampleVO s) {
        var data = new LinkedHashMap<String, Object>();
        data.put("orderId", c.getOrderId()); data.put("connectionId", c.getId());
        data.put("modelCode", c.getModelCode()); data.put("modelName", c.getModelName());
        data.put("sampleId", s.sampleId()); data.put("sequence", s.sequence()); data.put("capturedAt", s.capturedAt());
        data.put("latitude", s.latitude()); data.put("longitude", s.longitude()); data.put("altitude", s.altitude());
        data.put("speed", s.speed()); data.put("battery", s.battery()); data.put("payloadKg", s.payloadKg());
        data.put("flying", s.flying());
        var e = new WsEnvelope();
        e.setType(WsMessageNames.TYPE_EVENT); e.setName(WsMessageNames.FLIGHT_TELEMETRY);
        e.setDeviceId(c.getSerialNumber()); e.setTimestamp(s.capturedAt()); e.setData(data);
        return e;
    }

    private void deliver(String orderId, WsEnvelope envelope) {
        var targets = ConcurrentHashMap.<WebSocketSession>newKeySet();
        targets.addAll(supervisors);
        targets.addAll(watchers.getOrDefault(orderId, Set.of()));
        for (var session : targets) {
            if (session.isOpen()) messages.send(session, envelope);
            else unregister(session);
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}

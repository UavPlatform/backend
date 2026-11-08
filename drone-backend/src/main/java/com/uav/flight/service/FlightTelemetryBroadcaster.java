package com.uav.flight.service;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.flight.pojo.vo.FlightEventVO;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web realtime push of persisted flight telemetry over {@code /ws/web} (REQ-SUP-MON-005/007, REQ-SHIP-EXEC-004).
 * Supervisors (role 2) without {@code orderId} receive every order; with {@code ?orderId=} a session receives one
 * order and must be its shipper or a supervisor. Only events already committed to the projection are pushed, and
 * each event keeps its own capture time: no combined sample is synthesized (ADR-0014).
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
    public void publish(String orderId, List<FlightEventVO> events) {
        if (events.isEmpty()) return;
        Runnable send = () -> {
            for (var event : events) deliver(orderId, envelope(orderId, event));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    static WsEnvelope envelope(String orderId, FlightEventVO event) {
        var data = new LinkedHashMap<String, Object>();
        data.put("orderId", orderId);
        data.put("eventId", event.eventId());
        data.put("changeSequence", event.changeSequence());
        data.put("serialNumber", event.serialNumber());
        data.put("serialSource", event.serialSource());
        data.put("modelCode", event.modelCode());
        data.put("type", event.type());
        data.put("capturedAt", event.capturedAt());
        data.put("receivedAt", event.receivedAt());
        data.put("payload", event.payload());
        var envelope = new WsEnvelope();
        envelope.setType(WsMessageNames.TYPE_EVENT); envelope.setName(WsMessageNames.FLIGHT_TELEMETRY);
        envelope.setDeviceId(event.serialNumber()); envelope.setTimestamp(event.capturedAt()); envelope.setData(data);
        return envelope;
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

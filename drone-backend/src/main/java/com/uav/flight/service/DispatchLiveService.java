package com.uav.flight.service;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.mapper.*;
import com.uav.flight.pojo.entity.*;
import com.uav.flight.pojo.vo.*;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** REQ-PILOT-EXEC-004 / REQ-SHIP-EXEC-004 / ADR-0009. No viewer can start or stop aircraft video. */
@Service
public class DispatchLiveService {
    private final DispatchOrderRepository orders;
    private final FlightConnectionRepository connections;
    private final DispatchLiveRepository sessions;
    private final DispatchLiveSigner signer;
    public DispatchLiveService(DispatchOrderRepository orders, FlightConnectionRepository connections,
            DispatchLiveRepository sessions, DispatchLiveSigner signer) {
        this.orders = orders; this.connections = connections; this.sessions = sessions; this.signer = signer;
    }
    @Transactional
    public DispatchLiveCredentialsVO start(Long pilot, String id, String cid, String key) {
        var order = locked(id); var c = requirePilot(order, pilot, cid);
        var retry = sessions.findByPilotIdAndRequestKey(pilot, key);
        if (retry.isPresent()) {
            var old = retry.get();
            if (!cid.equals(old.getConnectionId()) || !id.equals(old.getOrderId()) || !active(old)) throw conflict();
            return signer.issue(old, true);
        }
        var current = sessions.findByActiveConnectionId(cid).orElse(null);
        if (current != null) {
            if (active(current)) throw conflict();
            end(current); sessions.flush();
        }
        var s = new DispatchLiveSession();
        s.setId(UUID.randomUUID().toString()); s.setOrderId(id); s.setConnectionId(c.getId()); s.setPilotId(pilot);
        s.setRequestKey(key); s.setState("STARTING"); s.setOpenedAt(LocalDateTime.now());
        s.setActiveConnectionId(cid); s.setLeaseExpiresAt(System.currentTimeMillis() + 20_000);
        var ticket = signer.issue(s, true);
        try { sessions.saveAndFlush(s); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw conflict(); }
        return ticket;
    }
    @Transactional
    public DispatchLiveStatusVO heartbeat(Long pilot, String id, String cid, String liveId, String state) {
        if (!signer.available()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.TRTC_CREDENTIAL_FAILED);
        var order = locked(id); requirePilot(order, pilot, cid);
        var s = session(id, cid, liveId);
        if (!pilot.equals(s.getPilotId()) || !active(s)) throw conflict();
        s.setState(state); s.setLeaseExpiresAt(System.currentTimeMillis() + 20_000); sessions.saveAndFlush(s);
        return status(s);
    }
    @Transactional
    public void stop(Long pilot, String id, String cid, String liveId) {
        locked(id); var s = session(id, cid, liveId);
        if (!pilot.equals(s.getPilotId())) throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
        end(s);
    }
    @Transactional(readOnly = true)
    public DispatchLiveStatusVO current(Long caller, Integer role, String id) {
        var order = readable(caller, role, id);
        var c = connections.findByActiveOrderId(id).orElse(null);
        if (c == null || !"IN_TRANSIT".equals(order.getStage())) return status(null);
        return status(sessions.findByActiveConnectionId(c.getId()).orElse(null));
    }
    @Transactional
    public DispatchLiveCredentialsVO watch(Long caller, Integer role, String id, String liveId) {
        // The order lock serializes ticket issuance with cancellation, delivery and swaps.
        var order = locked(id); requireReader(order, caller, role);
        if (!"IN_TRANSIT".equals(order.getStage())) throw conflict();
        var c = connections.findByActiveOrderId(id).orElseThrow(this::conflict);
        var s = session(id, c.getId(), liveId);
        if (!active(s) || !"RUNNING".equals(s.getState())) throw conflict();
        return signer.issue(s, false);
    }
    /** Called by FlightRecordService in the same order-locked transaction. */
    @Transactional
    public void closeConnection(String cid) { sessions.findByActiveConnectionId(cid).ifPresent(this::end); }
    private void end(DispatchLiveSession s) {
        if (s.getEndedAt() != null) return;
        s.setState("STOPPED"); s.setEndedAt(LocalDateTime.now()); s.setLeaseExpiresAt(0); s.setActiveConnectionId(null);
        sessions.save(s);
    }
    private DispatchLiveStatusVO status(DispatchLiveSession s) {
        boolean active = s != null && active(s);
        return new DispatchLiveStatusVO(signer.available(), active ? s.getState() : "UNAVAILABLE",
                active ? s.getId() : null, active ? s.getConnectionId() : null, active ? s.getLeaseExpiresAt() : 0);
    }
    private boolean active(DispatchLiveSession s) { return s.getEndedAt() == null && s.getLeaseExpiresAt() > System.currentTimeMillis(); }
    private FlightConnection requirePilot(DispatchOrder o, Long pilot, String cid) {
        var c = connections.findById(cid).orElseThrow(this::missing);
        if (!o.getId().equals(c.getOrderId()) || !pilot.equals(o.getAssignedPilotId()) || !pilot.equals(c.getPilotId())
                || c.getAssignmentVersion() != o.getAssignmentVersion() || c.getEndedAt() != null
                || !Set.of("PILOT_ARRIVED", "IN_TRANSIT").contains(o.getStage())) throw conflict();
        return c;
    }
    private DispatchLiveSession session(String id, String cid, String liveId) {
        return sessions.findById(liveId).filter(s -> id.equals(s.getOrderId()) && cid.equals(s.getConnectionId())).orElseThrow(this::missing);
    }
    private DispatchOrder locked(String id) { return orders.lockOrder(id).orElseThrow(this::missing); }
    private DispatchOrder readable(Long caller, Integer role, String id) {
        var o = orders.findById(id).orElseThrow(this::missing); requireReader(o, caller, role); return o;
    }
    private void requireReader(DispatchOrder o, Long caller, Integer role) {
        if (!Objects.equals(role, 2) && !Objects.equals(caller, o.getUserId()) && !Objects.equals(caller, o.getAssignedPilotId())) throw missing();
    }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND); }
    private BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.ORDER_STATUS_INVALID); }
}

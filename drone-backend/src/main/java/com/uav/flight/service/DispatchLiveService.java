package com.uav.flight.service;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.mapper.DispatchLiveRepository;
import com.uav.flight.pojo.entity.DispatchLiveSession;
import com.uav.flight.pojo.vo.DispatchLiveCredentialsVO;
import com.uav.flight.pojo.vo.DispatchLiveStatusVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Order-scoped live sessions. The pilot owns start/heartbeat/stop; no telemetry connection is required. */
@Service
public class DispatchLiveService {
    private final DispatchOrderRepository orders;
    private final DispatchLiveRepository sessions;
    private final DispatchLiveSigner signer;

    public DispatchLiveService(DispatchOrderRepository orders, DispatchLiveRepository sessions, DispatchLiveSigner signer) {
        this.orders = orders; this.sessions = sessions; this.signer = signer;
    }

    @Transactional
    public DispatchLiveCredentialsVO start(Long pilot, String id, String key) {
        var order = locked(id); requirePilot(order, pilot);
        var retry = sessions.findByPilotIdAndRequestKey(pilot, key);
        if (retry.isPresent()) {
            var old = retry.get();
            if (!id.equals(old.getOrderId()) || !active(old)) throw conflict();
            return signer.issue(old, true);
        }
        sessions.findTopByOrderIdOrderByOpenedAtDesc(id).filter(this::active).ifPresent(this::end);
        var session = new DispatchLiveSession();
        session.setId(UUID.randomUUID().toString()); session.setOrderId(id); session.setPilotId(pilot);
        session.setRequestKey(key); session.setState("STARTING"); session.setOpenedAt(LocalDateTime.now());
        session.setLeaseExpiresAt(System.currentTimeMillis() + 20_000);
        try { sessions.saveAndFlush(session); } catch (org.springframework.dao.DataIntegrityViolationException e) { throw conflict(); }
        return signer.issue(session, true);
    }

    @Transactional
    public DispatchLiveStatusVO heartbeat(Long pilot, String id, String liveId, String state) {
        if (!signer.available()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.TRTC_CREDENTIAL_FAILED);
        var order = locked(id); requirePilot(order, pilot);
        var session = session(id, liveId);
        if (!pilot.equals(session.getPilotId()) || !isCurrent(session) || !active(session)) throw conflict();
        session.setState(state); session.setLeaseExpiresAt(System.currentTimeMillis() + 20_000); sessions.saveAndFlush(session);
        return status(session);
    }

    @Transactional
    public void stop(Long pilot, String id, String liveId) {
        locked(id); var session = session(id, liveId);
        if (!pilot.equals(session.getPilotId())) throw denied();
        if (isCurrent(session)) end(session);
    }

    @Transactional(readOnly = true)
    public DispatchLiveStatusVO current(Long caller, Integer role, String id) {
        var order = readable(caller, role, id);
        return status(sessions.findTopByOrderIdOrderByOpenedAtDesc(order.getId()).orElse(null));
    }

    @Transactional
    public DispatchLiveCredentialsVO watch(Long caller, Integer role, String id, String liveId) {
        var order = readable(caller, role, id); var session = session(order.getId(), liveId);
        if (!isCurrent(session) || !active(session) || !"RUNNING".equals(session.getState())) throw conflict();
        return signer.issue(session, false);
    }

    /** Kept source-compatible for order close flows; live sessions are now order-scoped. */
    @Transactional public void closeConnection(String ignoredConnectionId) { }

    private void end(DispatchLiveSession s) {
        if (s.getEndedAt() != null) return;
        s.setState("STOPPED"); s.setEndedAt(LocalDateTime.now()); s.setLeaseExpiresAt(0); sessions.save(s);
    }
    private DispatchLiveStatusVO status(DispatchLiveSession s) {
        boolean active = s != null && isCurrent(s) && active(s);
        return new DispatchLiveStatusVO(signer.available(), active ? s.getState() : "UNAVAILABLE", active ? s.getId() : null,
                active ? s.getLeaseExpiresAt() : 0);
    }
    private boolean active(DispatchLiveSession s) { return s.getEndedAt() == null && s.getLeaseExpiresAt() > System.currentTimeMillis(); }
    private boolean isCurrent(DispatchLiveSession s) {
        return sessions.findTopByOrderIdOrderByOpenedAtDesc(s.getOrderId()).map(x -> x.getId().equals(s.getId())).orElse(false);
    }
    private DispatchLiveSession session(String id, String liveId) {
        return sessions.findById(liveId).filter(s -> id.equals(s.getOrderId())).orElseThrow(this::missing);
    }
    private void requirePilot(DispatchOrder o, Long pilot) { if (!Objects.equals(pilot, o.getAssignedPilotId())) throw conflict(); }
    private DispatchOrder locked(String id) { return orders.lockOrder(id).orElseThrow(this::missing); }
    private DispatchOrder readable(Long caller, Integer role, String id) {
        var o = orders.findById(id).orElseThrow(this::missing);
        if (!Objects.equals(role, 2) && !Objects.equals(caller, o.getUserId()) && !Objects.equals(caller, o.getAssignedPilotId())) throw missing();
        return o;
    }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND); }
    private BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.ORDER_STATUS_INVALID); }
    private BusinessException denied() { return new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION); }
}

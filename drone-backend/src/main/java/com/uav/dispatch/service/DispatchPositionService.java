package com.uav.dispatch.service;
import com.uav.dispatch.mapper.DispatchPilotPositionRepository;
import com.uav.dispatch.pojo.dto.DispatchPositionDTO;
import com.uav.dispatch.pojo.entity.DispatchPilotPosition;
import com.uav.dispatch.pojo.vo.DispatchPositionVO;
import com.uav.server.config.DispatchNearbyConfig;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.user.pojo.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class DispatchPositionService {
    private final DispatchPilotPositionRepository positions;
    private final DispatchNearbyConfig config;
    private final EntityManager entities;
    public DispatchPositionService(DispatchPilotPositionRepository positions, DispatchNearbyConfig config, EntityManager entities) {
        this.positions = positions; this.config = config; this.entities = entities;
    }
    private void lockPilot(Long id) {
        var user = entities.find(User.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (user == null || !Integer.valueOf(1).equals(user.getRole()) || !Integer.valueOf(1).equals(user.getStatus()))
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
    }
    @Transactional
    public String begin(Long pilot) {
        lockPilot(pilot);
        var position = positions.findById(pilot).orElseGet(DispatchPilotPosition::new);
        position.setPilotId(pilot); position.setSessionKey(java.util.UUID.randomUUID().toString());
        position.setLatitude(null); position.setLongitude(null); position.setAccuracyMeters(null); position.setCapturedAt(null);
        position.setReceivedAt(System.currentTimeMillis()); position.setExpiresAt(0);
        return positions.saveAndFlush(position).getSessionKey();
    }
    @Transactional
    public DispatchPositionVO update(Long pilot, DispatchPositionDTO input) {
        lockPilot(pilot);
        long now = System.currentTimeMillis(), ttl = config.getLocationTtlSeconds() * 1000;
        if (!Double.isFinite(input.latitude()) || !Double.isFinite(input.longitude()) || !Double.isFinite(input.accuracyMeters())
                || input.capturedAt() > now + 30000 || input.capturedAt() <= now - ttl)
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        var position = positions.findById(pilot).filter(p -> input.sessionKey().equals(p.getSessionKey()))
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.INVALID_PARAM, "附近接单会话已结束，请重新开启"));
        if (position.getCapturedAt() != null && input.capturedAt() <= position.getCapturedAt()) return view(position);
        position.setPilotId(pilot); position.setLatitude(input.latitude()); position.setLongitude(input.longitude());
        position.setAccuracyMeters(input.accuracyMeters()); position.setCapturedAt(input.capturedAt());
        position.setReceivedAt(now); position.setExpiresAt(Math.min(now, input.capturedAt()) + ttl);
        return view(positions.saveAndFlush(position));
    }
    @Transactional
    public void clear(Long pilot, String sessionKey) {
        // An old stop retry must not clear a more recent activation on the same account.
        var user = entities.find(User.class, pilot, LockModeType.PESSIMISTIC_WRITE);
        if (user == null || !Integer.valueOf(1).equals(user.getRole())) throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
        positions.findById(pilot).filter(p -> sessionKey == null || sessionKey.equals(p.getSessionKey())).ifPresent(positions::delete);
    }
    private DispatchPositionVO view(DispatchPilotPosition p) { return new DispatchPositionVO(p.getReceivedAt(), p.getExpiresAt(), p.getAccuracyMeters(), "GCJ02"); }
}

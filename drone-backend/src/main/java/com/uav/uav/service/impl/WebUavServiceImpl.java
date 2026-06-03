package com.uav.uav.service.impl;

import com.uav.uav.mapper.UavRepository;
import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.UserRecord;
import com.uav.uav.pojo.entity.Uav;
import com.uav.uav.pojo.dto.UavStatusDto;
import com.uav.uav.pojo.vo.UavRuntimeStatusVo;
import com.uav.uav.pojo.vo.UavVo;
import com.uav.uav.pojo.vo.WebUavStatusVo;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.live.service.impl.LiveSessionSnapshot;
import com.uav.uav.service.UavStatusService;
import com.uav.uav.service.WebUavService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WebUavServiceImpl implements WebUavService {
    private static final long STATUS_STALE_MILLIS = 90000;

    @Autowired
    private UavRepository uavRepository;

    @Autowired
    private UserRecordRepository userRecordRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UavStatusService uavStatusService;

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private LiveSessionService liveSessionService;

    @Override
    @Transactional(readOnly = true)
    public List<UavVo> getUav() {
        return uavRepository.getAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UavVo> getOnlineUav() {
        return uavRepository.findUavByOnlineStatus('1');
    }

    @Override
    @Transactional(readOnly = true)
    public WebUavStatusVo getUavStatus(String deviceId) {
        Uav uav = getRegisteredUav(deviceId);
        UavStatusDto status = uavStatusService.getUavStatus(deviceId);
        if (status == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.UAV_STATUS_NOT_FOUND);
        }

        WebUavStatusVo vo = new WebUavStatusVo();
        vo.setId(uav.getId());
        vo.setUavName(uav.getUavName());
        vo.setDjiId(uav.getDjiId());
        vo.setWsConnected(appWebSocketService.isConnected(deviceId));
        vo.setLiveState(getLiveState(deviceId));
        vo.setLatestStatus(toRuntimeStatus(status));
        return vo;
    }

    @Override
    @Transactional(readOnly = true)
    public Uav getRegisteredUav(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "deviceId 不能为空");
        }
        return uavRepository.findByDjiId(deviceId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.UAV_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRecord> getUserRecord(String userName) {
        if (!userRepository.existsByUserName(userName)) {
            return List.of();
        }
        return userRecordRepository.findAllByUserName(userName);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserRecord> getUserRecord(String userName, Pageable pageable) {
        if (!userRepository.existsByUserName(userName)) {
            return Page.empty(pageable);
        }
        return userRecordRepository.findAllByUserName(userName, pageable);
    }

    private String getLiveState(String deviceId) {
        LiveSessionSnapshot snapshot = liveSessionService.getSnapshot(deviceId);
        return snapshot.getState().name();
    }

    private UavRuntimeStatusVo toRuntimeStatus(UavStatusDto status) {
        if (status == null) {
            return null;
        }

        UavRuntimeStatusVo vo = new UavRuntimeStatusVo();
        vo.setDeviceId(status.getDeviceId());
        vo.setUavId(status.getUavId());
        vo.setUavName(status.getUavName());
        vo.setLongitude(status.getLongitude());
        vo.setLatitude(status.getLatitude());
        vo.setAltitude(status.getAltitude());
        vo.setSpeed(status.getSpeed());
        vo.setBattery(status.getBattery());
        vo.setFlightStatus(status.getFlightStatus());
        vo.setOperation(status.getOperation());
        vo.setTimestamp(status.getTimestamp());
        vo.setReceivedAt(status.getReceivedAt());
        long referenceTime = status.getReceivedAt() > 0 ? status.getReceivedAt() : status.getTimestamp();
        vo.setStale(referenceTime <= 0 || System.currentTimeMillis() - referenceTime > STATUS_STALE_MILLIS);
        return vo;
    }
}

package com.drone.service.impl;

import com.drone.mapper.UavRepository;
import com.drone.mapper.UserRecordRepository;
import com.drone.mapper.UserRepository;
import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.entity.Uav;
import com.drone.pojo.dto.UavStatusDto;
import com.drone.pojo.vo.UavRuntimeStatusVo;
import com.drone.pojo.vo.UavVo;
import com.drone.pojo.vo.WebUavStatusVo;
import com.drone.server.exception.ApiErrorCode;
import com.drone.server.exception.BusinessException;
import com.drone.service.AppWebSocketService;
import com.drone.service.LiveSessionService;
import com.drone.service.UavStatusService;
import com.drone.service.WebUavService;
import com.drone.service.impl.LiveSessionSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
    public UavVo[] getUav() {
        return uavRepository.getAll();
    }

    @Override
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
    public Uav getRegisteredUav(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "deviceId 不能为空");
        }
        Uav uav = uavRepository.findByDjiId(deviceId);
        if (uav == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.UAV_NOT_FOUND);
        }
        return uav;
    }

    @Override
    public List<UserRecord> getUserRecord(String userName) {

        List<UserRecord> records;
        if(userRepository.findByUserName(userName)!=null){
            try {
                records = userRecordRepository.findAllByUserName(userName);
                if (records.isEmpty()){
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return records;
        }else{
            throw new RuntimeException("用户未注册");
        }
    }

    @Override
    public Page<UserRecord> getUserRecord(String userName, Pageable pageable) {
        if(userRepository.findByUserName(userName)!=null){
            try {
                return userRecordRepository.findAllByUserName(userName, pageable);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }else{
            throw new RuntimeException("用户未注册");
        }
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

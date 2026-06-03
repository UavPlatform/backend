package com.uav.uav.service.impl;

import com.uav.uav.mapper.UavRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.uav.pojo.entity.Uav;
import com.uav.server.enums.ApiErrorCode;
import com.uav.uav.pojo.vo.AdminStatisticsVO;
import com.uav.uav.pojo.vo.LiveUavVO;
import com.uav.live.service.impl.LiveSessionSnapshot;
import com.uav.server.exception.BusinessException;
import com.uav.live.service.LiveSessionService;
import com.uav.uav.service.UavManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class UavManagementServiceImpl implements UavManagementService {

    @Autowired
    private UavRepository uavRepository;

    @Autowired
    private LiveSessionService liveSessionService;

    @Autowired
    private UserRepository userRepository;

    @Override
    @Transactional
    public boolean updateUavAvailable(String djiId, Character isAvailable) {
        if (djiId == null || djiId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "设备ID不能为空");
        }
        if (isAvailable == null || (!isAvailable.equals('0') && !isAvailable.equals('1'))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "isAvailable 必须为 0 或 1");
        }
        int result = uavRepository.updateUavAvailableByDjiId(djiId, isAvailable);
        return result > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Uav> getUav() {
        return uavRepository.findAllUav();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LiveUavVO> getLiveUav() {
        List<LiveSessionSnapshot> runningSessions = liveSessionService.getAllRunningSessions();
        List<LiveUavVO> result = new ArrayList<>();

        for (LiveSessionSnapshot session : runningSessions) {
            var uavOpt = uavRepository.findByDjiId(session.getDeviceId());
            if (uavOpt.isEmpty()) {
                continue;
            }
            Uav uav = uavOpt.get();
            LiveUavVO vo = new LiveUavVO();
            vo.setDeviceId(session.getDeviceId());
            vo.setUavName(uav.getUavName());
            vo.setRoomId(session.getRoomId());
            vo.setRequestId(session.getRequestId());
            vo.setUpdatedAt(session.getUpdatedAt());
            vo.setOnlineStatus(uav.getOnlineStatus() != null ? String.valueOf(uav.getOnlineStatus()) : "0");
            vo.setIsAvailable(uav.getIsAvailable() != null ? String.valueOf(uav.getIsAvailable()) : "1");
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Uav getUavByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "设备ID不能为空");
        }
        return uavRepository.findByDjiId(deviceId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.UAV_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminStatisticsVO getAdminStatistics() {
        List<Uav> allUavs = uavRepository.findAllUav();
        List<LiveSessionSnapshot> runningSessions = liveSessionService.getAllRunningSessions();
        long totalUsers = userRepository.count();

        int onlineUavs = 0;
        int availableUavs = 0;

        for (Uav uav : allUavs) {
            if (uav.getOnlineStatus() != null && uav.getOnlineStatus() == '1') {
                onlineUavs++;
            }
            if (uav.getIsAvailable() != null && uav.getIsAvailable() == '1') {
                availableUavs++;
            }
        }

        AdminStatisticsVO vo = new AdminStatisticsVO();
        vo.setTotalUavs(allUavs.size());
        vo.setOnlineUavs(onlineUavs);
        vo.setAvailableUavs(availableUavs);
        vo.setLiveUavs(runningSessions.size());
        vo.setOfflineUavs(allUavs.size() - onlineUavs);
        vo.setUnavailableUavs(allUavs.size() - availableUavs);
        vo.setTotalUsers(totalUsers);
        return vo;
    }
}

package com.drone.service.impl;

import com.drone.mapper.AdminRepository;
import com.drone.mapper.UavRepository;
import com.drone.mapper.UserRepository;
import com.drone.pojo.dto.AdminDto;
import com.drone.pojo.entity.Admin;
import com.drone.pojo.entity.Uav;
import com.drone.pojo.enums.ApiErrorCode;
import com.drone.pojo.vo.admin.AdminStatisticsVO;
import com.drone.pojo.vo.admin.LiveUavVO;
import com.drone.server.exception.BusinessException;
import com.drone.server.util.PasswordUtil;
import com.drone.service.AdminService;
import com.drone.service.LiveSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class AdminServiceImpl implements AdminService {

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private UavRepository uavRepository;

    @Autowired
    private LiveSessionService liveSessionService;

    @Autowired
    private UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Admin tryToLogin(AdminDto adminDto) {
        String name = adminDto.getName();
        String password = adminDto.getPassword();

        if (name == null || name.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    ApiErrorCode.INVALID_PARAM, "用户名和密码不能为空");
        }

        Admin admin = adminRepository.findByName(name)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        ApiErrorCode.INVALID_PARAM, "用户名或密码错误"));

        if (!PasswordUtil.matches(password, admin.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    ApiErrorCode.INVALID_PARAM, "用户名或密码错误");
        }

        return admin;
    }

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

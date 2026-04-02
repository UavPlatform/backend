package com.drone.service.impl;

import com.drone.mapper.AdminRepository;
import com.drone.mapper.UavRepository;
import com.drone.mapper.UserRepository;
import com.drone.pojo.dto.AdminDto;
import com.drone.pojo.entity.Admin;
import com.drone.pojo.entity.Uav;
import com.drone.server.exception.UnauthorizedException;
import com.drone.service.AdminService;
import com.drone.service.LiveSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public Admin tryToLogin(AdminDto adminDto) {
        String name = adminDto.getName();
        String password = adminDto.getPassword();

        if (name == null || name.isBlank() || password == null || password.isBlank()) {
            throw new UnauthorizedException("用户名和密码不能为空");
        }

        try {
            Admin admin = adminRepository.findByNameAndPassword(name, password);
            if (admin == null) {
                throw new UnauthorizedException("用户名或密码错误");
            }
            return admin;
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.error("管理员登录失败: {}", e.getMessage());
            throw new RuntimeException("登录失败，请稍后重试");
        }
    }

    @Override
    public boolean updateUavAvailable(String djiId, Character isAvailable) {
        if (djiId == null || djiId.isBlank()) {
            throw new IllegalArgumentException("设备ID不能为空");
        }
        if (isAvailable == null || (!isAvailable.equals('0') && !isAvailable.equals('1'))) {
            throw new IllegalArgumentException(" 0(不可用) 或 1(可用)");
        }
        try {
            int result = uavRepository.updateUavAvailableByDjiId(djiId, isAvailable);
            return result > 0;
        } catch (Exception e) {
            log.error("更新无人机可用状态失败: {}", e.getMessage());
            throw new RuntimeException("更新失败，请稍后重试");
        }
    }

    @Override
    public Uav[] getUav() {
        try {
            List<Uav> uavs = uavRepository.findAllUav();
            return uavs.toArray(new Uav[0]);
        } catch (Exception e) {
            log.error("查询所有无人机失败: {}", e.getMessage());
            throw new RuntimeException("查询失败，请稍后重试");
        }
    }

    @Override
    public List<Map<String, Object>> getLiveUav() {
        try {
            List<LiveSessionSnapshot> runningSessions = liveSessionService.getAllRunningSessions();
            List<Map<String, Object>> liveUavList = new ArrayList<>();

            for (LiveSessionSnapshot session : runningSessions) {
                Uav uav = uavRepository.findByDjiId(session.getDeviceId());
                if (uav != null) {
                    Map<String, Object> liveUavInfo = new HashMap<>();
                    liveUavInfo.put("deviceId", session.getDeviceId());
                    liveUavInfo.put("uavName", uav.getUavName());
                    liveUavInfo.put("roomId", session.getRoomId());
                    liveUavInfo.put("requestId", session.getRequestId());
                    liveUavInfo.put("updatedAt", session.getUpdatedAt());
                    liveUavInfo.put("onlineStatus", uav.getOnlineStatus());
                    liveUavInfo.put("isAvailable", uav.getIsAvailable());
                    liveUavList.add(liveUavInfo);
                }
            }
            return liveUavList;
        } catch (Exception e) {
            log.error("查询直播无人机失败: {}", e.getMessage());
            throw new RuntimeException("查询失败，请稍后重试");
        }
    }

    @Override
    public Uav getUavByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("设备ID不能为空");
        }
        try {
            Uav uav = uavRepository.findByDjiId(deviceId);
            if (uav == null) {
                throw new RuntimeException("无人机不存在");
            }
            return uav;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询无人机详情失败: {}", e.getMessage());
            throw new RuntimeException("查询无人机详情失败，请稍后重试");
        }
    }

    @Override
    public Map<String, Object> getAdminStatistics() {
        try {
            Map<String, Object> statistics = new HashMap<>();

            List<Uav> allUavs = uavRepository.findAllUav();
            List<LiveSessionSnapshot> runningSessions = liveSessionService.getAllRunningSessions();
            long totalUsers = userRepository.count();

            int totalUavs = allUavs.size();
            int onlineUavs = 0;
            int availableUavs = 0;
            int liveUavs = runningSessions.size();

            log.info("查询到 {} 架无人机", totalUavs);
            for (Uav uav : allUavs) {
                log.info("无人机 {} - onlineStatus: {}, isAvailable: {}", 
                    uav.getUavName(), uav.getOnlineStatus(), uav.getIsAvailable());
                if (uav.getOnlineStatus() != null && uav.getOnlineStatus() == '1') {
                    onlineUavs++;
                }
                if (uav.getIsAvailable() != null && uav.getIsAvailable() == '1') {
                    availableUavs++;
                }
            }

            statistics.put("totalUavs", totalUavs);
            statistics.put("onlineUavs", onlineUavs);
            statistics.put("availableUavs", availableUavs);
            statistics.put("liveUavs", liveUavs);
            statistics.put("offlineUavs", totalUavs - onlineUavs);
            statistics.put("unavailableUavs", totalUavs - availableUavs);
            statistics.put("totalUsers", totalUsers);

            log.info("统计结果: 总数={}, 在线={}, 可用={}, 直播中={}", 
                totalUavs, onlineUavs, availableUavs, liveUavs);

            return statistics;
        } catch (Exception e) {
            log.error("获取统计信息失败: {}", e.getMessage());
            throw new RuntimeException("获取统计信息失败，请稍后重试");
        }
    }
}

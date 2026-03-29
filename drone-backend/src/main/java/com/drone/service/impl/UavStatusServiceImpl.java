package com.drone.service.impl;

import com.drone.pojo.dto.UavStatusDto;
import com.drone.service.UavStatusService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UavStatusServiceImpl implements UavStatusService {
    // 存储无人机状态（无人机ID -> 状态）
    private final Map<Long, UavStatusDto> uavStatusMap = new ConcurrentHashMap<>();
    // 存储无人机状态（设备ID -> 状态）
    private final Map<String, UavStatusDto> deviceStatusMap = new ConcurrentHashMap<>();

    /**
     * 更新无人机状态
     * @param status 无人机状态
     */
    @Override
    public void updateUavStatus(UavStatusDto status) {
        if (status == null) {
            return;
        }
        if (status.getUavId() != null) {
            uavStatusMap.put(status.getUavId(), status);
        }
        if (status.getDeviceId() != null && !status.getDeviceId().isBlank()) {
            deviceStatusMap.put(status.getDeviceId(), status);
        }
    }

    @Override
    public void updateUavStatus(String deviceId, UavStatusDto status) {
        if (status == null) {
            return;
        }
        status.setDeviceId(deviceId);
        if (status.getReceivedAt() <= 0) {
            status.setReceivedAt(System.currentTimeMillis());
        }
        updateUavStatus(status);
    }

    /**
     * 获取无人机状态
     * @param uavId 无人机ID
     * @return 无人机状态
     */
    @Override
    public UavStatusDto getUavStatus(Long uavId) {
        return uavStatusMap.get(uavId);
    }

    @Override
    public UavStatusDto getUavStatus(String deviceId) {
        return deviceStatusMap.get(deviceId);
    }

    /**
     * 获取所有无人机状态
     * @return 所有无人机状态
     */
    @Override
    public Map<Long, UavStatusDto> getAllUavStatus() {
        return uavStatusMap;
    }

    @Override
    public Map<String, UavStatusDto> getAllUavStatusByDeviceId() {
        return deviceStatusMap;
    }
}

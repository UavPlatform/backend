package com.uav.user.service.impl;

import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.aircraft.service.AircraftModelService;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.service.RiderUavService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RiderUavServiceImpl implements RiderUavService {

    @Autowired
    private RiderUavRepository riderUavRepository;

    @Autowired
    private AircraftModelService aircraftModelService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindDrone(Long userId, String djiId, Long aircraftModelId) {
        if (djiId == null || djiId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "无人机ID不能为空");
        }
        // 机型非空时必须存在且启用；null 仅出现在注册等未映射路径（存量兼容），
        // 未映射设备由 requireTransportDevice 在吊运应征时拦截。
        if (aircraftModelId != null) {
            aircraftModelService.requireSelectable(aircraftModelId);
        }
        if (riderUavRepository.existsByDjiId(djiId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "该无人机已被绑定");
        }
        RiderUav drone = new RiderUav();
        drone.setUserId(userId);
        drone.setDjiId(djiId);
        drone.setAircraftModelId(aircraftModelId);
        riderUavRepository.save(drone);
    }

    @Override
    public List<RiderUav> listDrones(Long userId) {
        return riderUavRepository.findByUserId(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindDrone(Long userId, String djiId, Long aircraftModelId) {
        if (djiId == null || djiId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "无人机ID不能为空");
        }
        RiderUav binding = riderUavRepository.findByUserIdAndDjiId(userId, djiId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "未找到该无人机绑定记录"));
        if (aircraftModelId != null && !aircraftModelId.equals(binding.getAircraftModelId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_MISMATCH,
                    "解绑机型与绑定记录不一致");
        }
        riderUavRepository.delete(binding);
    }

    @Override
    public AircraftModel requireTransportDevice(Long userId, Long aircraftModelId) {
        AircraftModel model = aircraftModelService.requireTransportCapable(aircraftModelId);
        List<RiderUav> bindings = riderUavRepository.findByUserId(userId);
        if (bindings.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.UAV_NOT_FOUND,
                    "未绑定无人机，不能用于吊运应征");
        }
        if (bindings.stream().anyMatch(b -> aircraftModelId.equals(b.getAircraftModelId()))) {
            return model;
        }
        if (bindings.stream().anyMatch(b -> b.getAircraftModelId() == null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_REQUIRED,
                    "绑定设备未映射机型，不能用于吊运应征");
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_MISMATCH,
                "绑定设备与应征机型不一致");
    }
}

package com.uav.user.service.impl;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindDrone(Long userId, String djiId) {
        if (djiId == null || djiId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "无人机ID不能为空");
        }
        if (riderUavRepository.existsByDjiId(djiId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "该无人机已被绑定");
        }
        RiderUav drone = new RiderUav();
        drone.setUserId(userId);
        drone.setDjiId(djiId);
        riderUavRepository.save(drone);
    }

    @Override
    public List<RiderUav> listDrones(Long userId) {
        return riderUavRepository.findByUserId(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindDrone(Long userId, String djiId) {
        if (djiId == null || djiId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "无人机ID不能为空");
        }
        int deleted = riderUavRepository.deleteByUserIdAndDjiId(userId, djiId);
        if (deleted == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "未找到该无人机绑定记录");
        }
    }
}

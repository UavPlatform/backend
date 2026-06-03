package com.uav.uav.service.impl;

import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.dto.UavDto;
import com.uav.uav.pojo.entity.Uav;
import com.uav.server.enums.ApiErrorCode;
import com.uav.uav.pojo.vo.UavVo;
import com.uav.server.exception.BusinessException;
import com.uav.uav.service.AppUavService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
public class AppUavServiceImpl implements AppUavService {

    @Autowired
    private UavRepository uavRepository;

    @Override
    @Transactional
    public UavVo tryToAddUav(UavDto uavDto) {
        String name = uavDto.getUavName();
        Character status = uavDto.getOnlineStatus();
        String djiId = uavDto.getDjiId();
        String controllerModel = uavDto.getControllerModel();

        if (name == null || name.isBlank() || status == null || djiId == null || djiId.isBlank()
                || controllerModel == null || controllerModel.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "无人机名称、在线状态、DjiId或控制器型号为空");
        }
        if (uavRepository.findByDjiId(djiId).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.INVALID_PARAM,
                    "无人机DjiId已注册");
        }
        if (uavRepository.findUavByUavName(name).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.INVALID_PARAM,
                    "无人机名字已存在");
        }

        Uav uav = new Uav();
        uav.setUavName(name);
        uav.setOnlineStatus(status);
        uav.setDjiId(djiId);
        uav.setControllerModel(controllerModel);
        uav.setIsAvailable('1');
        uavRepository.save(uav);
        return new UavVo(uav.getId(), uav.getUavName(), uav.getDjiId(), uav.getControllerModel(), uav.getIsAvailable());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UavVo> getAllUav() {
        return uavRepository.getAll();
    }

    @Override
    @Transactional
    public void updateUav(UavDto uavDto) {
        Uav uav = uavRepository.findByDjiId(uavDto.getDjiId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.UAV_NOT_FOUND));

        if (uavDto.getUavName() != null && !uavDto.getUavName().isBlank()
                && !uavDto.getUavName().equals(uav.getUavName())) {
            if (uavRepository.findUavByUavName(uavDto.getUavName()).isPresent()) {
                throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.INVALID_PARAM,
                        "无人机名字已存在");
            }
            uav.setUavName(uavDto.getUavName());
        }
        if (uavDto.getOnlineStatus() != null) {
            uav.setOnlineStatus(uavDto.getOnlineStatus());
        }
        if (uavDto.getControllerModel() != null && !uavDto.getControllerModel().isBlank()) {
            uav.setControllerModel(uavDto.getControllerModel());
        }
        uav.setIsAvailable('1');
        uavRepository.save(uav);
    }
}

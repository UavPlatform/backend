package com.drone.service.impl;

import com.drone.mapper.UavRepository;
import com.drone.pojo.dto.UavDto;
import com.drone.pojo.entity.Uav;
import com.drone.pojo.vo.UavVo;
import com.drone.service.AppUavService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;


@Service
public class AppUavServiceImpl implements AppUavService {

    @Autowired
    private UavRepository uavRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UavVo tryToAddUav(UavDto uavDto) {
        String name = uavDto.getUavName();
        Character status = uavDto.getOnlineStatus();

        if (name != null && status != null) {
            UavVo uavVo = uavRepository.findUavByUavName(name);
            if (uavVo != null && uavVo.getId() != null) {
                throw new RuntimeException("无人机名字已存在");
            } else {
                Uav uav = new Uav();
                uav.setId(uavRepository.findMaxId() + 1);
                uav.setUavName(name);
                uav.setUavCreateTime(LocalDateTime.now());
                uav.setOnlineStatus(status);
                uavRepository.save(uav);
                return new UavVo(uav.getId(), uav.getUavName());
            }
        }
        throw new RuntimeException("无人机名称或在线状态为空");
    }

    @Override
    public UavVo[] getAllUav() {
        return uavRepository.getAll();
    }
}


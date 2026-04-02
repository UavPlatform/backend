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
        String djiId = uavDto.getDjiId();

        // 验证参数
        if (name == null || status == null || djiId == null) {
            throw new RuntimeException("无人机名称、在线状态或DjiId为空");
        }
        if (uavRepository.findByDjiId(djiId) != null) {
            throw new RuntimeException("无人机DjiId已注册");
        }
        UavVo uavVo = uavRepository.findUavByUavName(name);
        if (uavVo != null && uavVo.getId() != null) {
            throw new RuntimeException("无人机名字已存在");
        }

        Uav uav = new Uav();
        uav.setUavName(name);
        uav.setUavCreateTime(LocalDateTime.now());
        uav.setOnlineStatus(status);
        uav.setDjiId(djiId);
        uav.setControllerModel(uavDto.getControllerModel());
        uavRepository.save(uav);
        return new UavVo(uav.getId(), uav.getUavName(), uav.getDjiId(), uav.getControllerModel());
    }

    @Override
    public UavVo[] getAllUav() {
        return uavRepository.getAll();
    }

    @Override
    public String updateUav(UavDto uavDto) {
        Uav uav = uavRepository.findByDjiId(uavDto.getDjiId());
        if(uav==null){
            throw  new RuntimeException("无人机不存在");
        }else{
            uav.setUavName(uavDto.getUavName());
            uav.setOnlineStatus(uavDto.getOnlineStatus());
            uav.setDjiId(uavDto.getDjiId());
            uav.setIsAvailable('1');
            uav.setControllerModel(uavDto.getControllerModel());
            uavRepository.save(uav);
        }
        return "更新成功";
    }
}


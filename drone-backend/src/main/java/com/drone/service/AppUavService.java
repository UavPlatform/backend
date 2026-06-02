package com.drone.service;

import com.drone.pojo.dto.UavDto;
import com.drone.pojo.vo.uav.UavVo;

import java.util.List;


public interface AppUavService {
    UavVo tryToAddUav(UavDto uavDto);

    List<UavVo> getAllUav();

    void updateUav(UavDto uavDto);
}

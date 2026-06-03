package com.uav.uav.service;

import com.uav.uav.pojo.dto.UavDto;
import com.uav.uav.pojo.vo.UavVo;

import java.util.List;


public interface AppUavService {
    UavVo tryToAddUav(UavDto uavDto);

    List<UavVo> getAllUav();

    void updateUav(UavDto uavDto);
}

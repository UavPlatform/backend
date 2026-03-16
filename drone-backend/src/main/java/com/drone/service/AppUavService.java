package com.drone.service;

import com.drone.pojo.dto.UavDto;
import com.drone.pojo.vo.UavVo;


public interface AppUavService {
    UavVo tryToAddUav(UavDto uavDto);

    UavVo[] getAllUav();
}

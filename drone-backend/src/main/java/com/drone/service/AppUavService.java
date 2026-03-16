package com.drone.service;

import com.drone.pojo.dto.UavDto;
import com.drone.pojo.vo.UavVo;

public interface AppUavAService {
    UavVo tryToAddUav(UavDto uavDto);
}

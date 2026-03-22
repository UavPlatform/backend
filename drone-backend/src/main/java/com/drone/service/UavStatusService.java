package com.drone.service;

import com.drone.pojo.dto.UavStatusDto;

import java.util.Map;

public interface UavStatusService {
    void updateUavStatus(UavStatusDto status);

    UavStatusDto getUavStatus(Long uavId);

    Map<Long, UavStatusDto> getAllUavStatus();
}

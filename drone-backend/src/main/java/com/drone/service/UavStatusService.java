package com.drone.service;

import com.drone.pojo.dto.UavStatusDto;

import java.util.Map;

public interface UavStatusService {
    void updateUavStatus(UavStatusDto status);

    void updateUavStatus(String deviceId, UavStatusDto status);

    UavStatusDto getUavStatus(Long uavId);

    UavStatusDto getUavStatus(String deviceId);

    Map<Long, UavStatusDto> getAllUavStatus();

    Map<String, UavStatusDto> getAllUavStatusByDeviceId();
}

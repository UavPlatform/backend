package com.uav.rider.service;

import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.rider.pojo.vo.RiderInfoVO;

public interface RiderInfoService {
    RiderInfoVO getInfo(Long userId);
    RiderInfoVO editInfo(Long userId, RiderInfoDto dto);
}

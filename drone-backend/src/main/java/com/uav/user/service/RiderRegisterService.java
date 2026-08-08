package com.uav.user.service;

import com.uav.user.pojo.dto.RiderRegisterDto;
import com.uav.user.pojo.entity.User;

public interface RiderRegisterService {
    User register(RiderRegisterDto dto);
}

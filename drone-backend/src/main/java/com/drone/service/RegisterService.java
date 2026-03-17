package com.drone.service;

import com.drone.pojo.dto.UserRegisterDto;
import com.drone.pojo.vo.RegisterVo;

public interface RegisterService {
    RegisterVo tryToRegister(UserRegisterDto userRegisterDto);
}

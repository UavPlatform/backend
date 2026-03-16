package com.drone.service;

import com.drone.pojo.dto.UserRegisterDto;
import com.drone.pojo.vo.RegisterVo;
import org.springframework.stereotype.Service;

public interface RegisterService {
    RegisterVo tryToRegister(UserRegisterDto userRegisterDto);
}

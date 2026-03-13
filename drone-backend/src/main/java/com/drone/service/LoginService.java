package com.drone.service;

import com.drone.pojo.dto.UserLoginDto;

public interface LoginService {

    boolean tryToLogin(UserLoginDto userLoginDto);
}

package com.drone.service;

import com.drone.pojo.dto.UserLoginDto;
import com.drone.pojo.entity.User;

public interface LoginService {

    User tryToLogin(UserLoginDto userLoginDto);
}

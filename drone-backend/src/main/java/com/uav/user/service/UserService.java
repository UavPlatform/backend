package com.uav.user.service;

import com.uav.user.pojo.dto.UserLoginDto;
import com.uav.user.pojo.dto.UserRegisterDto;
import com.uav.user.pojo.entity.User;
import com.uav.user.pojo.vo.RegisterVo;

public interface UserService {
    void updateRole(Long userId, Integer role);
    RegisterVo tryToRegister(UserRegisterDto userRegisterDto);
    User tryToLogin(UserLoginDto userLoginDto);
}

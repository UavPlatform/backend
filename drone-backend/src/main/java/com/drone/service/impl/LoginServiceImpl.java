package com.drone.service.impl;

import com.drone.mapper.UserRepository;
import com.drone.pojo.dto.UserLoginDto;
import com.drone.pojo.entity.User;
import com.drone.pojo.enums.ApiErrorCode;
import com.drone.server.exception.BusinessException;
import com.drone.server.util.PasswordUtil;
import com.drone.service.LoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LoginServiceImpl implements LoginService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public User tryToLogin(UserLoginDto userLoginDto) {
        String userName = userLoginDto.getUserName();
        String password = userLoginDto.getPassword();

        if (userName == null || userName.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ApiErrorCode.INVALID_PARAM, "用户名或密码为空");
        }

        User user = userRepository.findByUserName(userName)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        ApiErrorCode.INVALID_PARAM, "用户名或密码错误"));

        if (!PasswordUtil.matches(password, user.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ApiErrorCode.INVALID_PARAM, "用户名或密码错误");
        }

        return user;
    }
}

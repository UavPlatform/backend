package com.uav.user.service.impl;

import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.dto.UserLoginDto;
import com.uav.user.pojo.dto.UserRegisterDto;
import com.uav.user.pojo.entity.User;
import com.uav.user.pojo.vo.RegisterVo;
import com.uav.user.service.UserService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public String getName(Long userId) {
        return userRepository.findById(userId).map(User::getUserName).orElse(null);
    }

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


    @Override
    public void updateRole(Long userId, Integer role) {

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegisterVo tryToRegister(UserRegisterDto userRegisterDto) {
        String name = userRegisterDto.getUserName();
        String password = userRegisterDto.getPassword();

        if (name == null || name.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "用户名或密码为空");
        }

        if (userRepository.existsByUserName(name)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "用户名已存在");
        }

        User user = new User();
        user.setUserName(name);
        user.setPassword(PasswordUtil.hash(password));
        user.setStatus(1);
        user.setRole(0);

        try {
            userRepository.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "用户名已存在");
        }

        return new RegisterVo(user.getId(), user.getUserName());
    }
}

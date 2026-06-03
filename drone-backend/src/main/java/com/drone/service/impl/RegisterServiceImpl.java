package com.drone.service.impl;

import com.drone.mapper.UserRepository;
import com.drone.pojo.dto.UserRegisterDto;
import com.drone.pojo.entity.User;
import com.drone.pojo.enums.ApiErrorCode;
import com.drone.pojo.vo.auth.RegisterVo;
import com.drone.server.exception.BusinessException;
import com.drone.server.util.PasswordUtil;
import com.drone.service.RegisterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterServiceImpl implements RegisterService {

    @Autowired
    private UserRepository userRepository;

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

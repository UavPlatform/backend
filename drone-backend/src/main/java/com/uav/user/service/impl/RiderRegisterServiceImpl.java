package com.uav.user.service.impl;

import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.dto.RiderRegisterDto;
import com.uav.user.pojo.entity.User;
import com.uav.user.service.RiderUavService;
import com.uav.user.service.RiderRegisterService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderRegisterServiceImpl implements RiderRegisterService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RiderUavService riderUavService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User register(RiderRegisterDto dto) {
        if (userRepository.existsByUserName(dto.getUserName())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "用户名已存在");
        }

        User user = new User();
        user.setUserName(dto.getUserName());
        user.setPassword(PasswordUtil.hash(dto.getPassword()));
        user.setStatus(1);
        user.setRole(1);
        user = userRepository.save(user);

        if (dto.getDjiId() != null && !dto.getDjiId().isBlank()) {
            riderUavService.bindDrone(user.getId(), dto.getDjiId());
        }

        return user;
    }
}

package com.uav.admin.service.impl;

import com.uav.admin.mapper.AdminRepository;
import com.uav.admin.pojo.dto.AdminDto;
import com.uav.admin.pojo.entity.Admin;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.PasswordUtil;
import com.uav.admin.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminServiceImpl implements AdminService {

    @Autowired
    private AdminRepository adminRepository;

    @Override
    @Transactional(readOnly = true)
    public Admin tryToLogin(AdminDto adminDto) {
        String name = adminDto.getName();
        String password = adminDto.getPassword();

        if (name == null || name.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    ApiErrorCode.INVALID_PARAM, "用户名和密码不能为空");
        }

        Admin admin = adminRepository.findByName(name)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        ApiErrorCode.INVALID_PARAM, "用户名或密码错误"));

        if (!PasswordUtil.matches(password, admin.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    ApiErrorCode.INVALID_PARAM, "用户名或密码错误");
        }

        return admin;
    }
}

package com.uav.admin.service;

import com.uav.admin.pojo.dto.AdminDto;
import com.uav.admin.pojo.entity.Admin;

public interface AdminService {
    Admin tryToLogin(AdminDto adminDto);
}

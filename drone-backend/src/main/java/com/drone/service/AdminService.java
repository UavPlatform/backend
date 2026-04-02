package com.drone.service;

import com.drone.pojo.dto.AdminDto;
import com.drone.pojo.entity.Admin;
import com.drone.pojo.entity.Uav;
import com.drone.service.impl.LiveSessionSnapshot;
import java.util.List;
import java.util.Map;

public interface AdminService {
    Admin tryToLogin(AdminDto adminDto);

    boolean updateUavAvailable(String deviceId, Character isAvailable);

    Uav[] getUav();

    List<Map<String, Object>> getLiveUav();

    Uav getUavByDeviceId(String deviceId);

    Map<String, Object> getAdminStatistics();
}
package com.drone.service;

import com.drone.pojo.dto.AdminDto;
import com.drone.pojo.entity.Admin;
import com.drone.pojo.entity.Uav;
import com.drone.pojo.vo.admin.AdminStatisticsVO;
import com.drone.pojo.vo.admin.LiveUavVO;

import java.util.List;

public interface AdminService {
    Admin tryToLogin(AdminDto adminDto);

    boolean updateUavAvailable(String deviceId, Character isAvailable);

    List<Uav> getUav();

    List<LiveUavVO> getLiveUav();

    Uav getUavByDeviceId(String deviceId);

    AdminStatisticsVO getAdminStatistics();
}

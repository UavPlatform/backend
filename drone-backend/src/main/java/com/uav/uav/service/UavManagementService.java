package com.uav.uav.service;

import com.uav.uav.pojo.entity.Uav;
import com.uav.uav.pojo.vo.AdminStatisticsVO;
import com.uav.uav.pojo.vo.LiveUavVO;

import java.util.List;

public interface UavManagementService {
    boolean updateUavAvailable(String deviceId, Character isAvailable);

    List<Uav> getUav();

    List<LiveUavVO> getLiveUav();

    Uav getUavByDeviceId(String deviceId);

    AdminStatisticsVO getAdminStatistics();
}

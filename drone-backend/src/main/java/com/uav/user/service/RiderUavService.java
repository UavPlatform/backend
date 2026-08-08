package com.uav.user.service;

import com.uav.user.pojo.entity.RiderUav;

import java.util.List;

public interface RiderUavService {
    void bindDrone(Long userId, String djiId);

    List<RiderUav> listDrones(Long userId);

    void unbindDrone(Long userId, String djiId);
}

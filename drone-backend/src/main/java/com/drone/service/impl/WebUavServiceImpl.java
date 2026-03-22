package com.drone.service.impl;

import com.drone.mapper.UavRepository;
import com.drone.pojo.vo.UavVo;
import com.drone.service.WebUavService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WebUavServiceImpl implements WebUavService {

    @Autowired
    private UavRepository uavRepository;

    @Override
    public UavVo[] getUav() {
        return uavRepository.getAll();
    }
}

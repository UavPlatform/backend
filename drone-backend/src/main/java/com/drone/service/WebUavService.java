package com.drone.service;

import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.entity.Uav;
import com.drone.pojo.vo.uav.UavVo;
import com.drone.pojo.vo.uav.WebUavStatusVo;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WebUavService {
    List<UavVo> getUav();

    List<UavVo> getOnlineUav();

    WebUavStatusVo getUavStatus(String deviceId);

    Uav getRegisteredUav(String deviceId);

    List<UserRecord> getUserRecord(String userName);
    Page<UserRecord> getUserRecord(String userName, Pageable pageable);
}

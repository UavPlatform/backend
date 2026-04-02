package com.drone.service;

import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.entity.Uav;
import com.drone.pojo.vo.UavVo;
import com.drone.pojo.vo.WebUavStatusVo;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WebUavService {
    UavVo[] getUav();

    UavVo[] getOnlineUav();

    WebUavStatusVo getUavStatus(String deviceId);

    Uav getRegisteredUav(String deviceId);

    List<UserRecord> getUserRecord(String userName);
    Page<UserRecord> getUserRecord(String userName, Pageable pageable);
}

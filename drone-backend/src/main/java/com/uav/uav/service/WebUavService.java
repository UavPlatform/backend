package com.uav.uav.service;

import com.uav.user.pojo.entity.UserRecord;
import com.uav.uav.pojo.entity.Uav;
import com.uav.uav.pojo.vo.UavVo;
import com.uav.uav.pojo.vo.WebUavStatusVo;
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

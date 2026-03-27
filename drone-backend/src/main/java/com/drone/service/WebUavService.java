package com.drone.service;

import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.vo.UavVo;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WebUavService {
    UavVo[] getUav();

    List<UserRecord> getUserRecord(String userName);
    Page<UserRecord> getUserRecord(String userName, Pageable pageable);
}

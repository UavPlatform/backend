package com.drone.mapper;

import com.drone.pojo.entity.Uav;
import com.drone.pojo.entity.UserRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserRecordRepository extends JpaRepository<UserRecord, Long> {
    List<UserRecord> findAllByUserName(String userName);
    Page<UserRecord> findAllByUserName(String userName, Pageable pageable);
}

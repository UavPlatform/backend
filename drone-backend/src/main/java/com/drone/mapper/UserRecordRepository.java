package com.drone.mapper;

import com.drone.pojo.entity.UserRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRecordRepository extends JpaRepository<UserRecord, Long> {
    List<UserRecord> findAllByUserName(String userName);
    Page<UserRecord> findAllByUserName(String userName, Pageable pageable);

    @Query("SELECT ur FROM UserRecord ur WHERE ur.userName = :userName AND ur.djiId = :djiId AND ur.end_time IS NULL ORDER BY ur.start_time DESC")
    List<UserRecord> findOpenRecords(@Param("userName") String userName, @Param("djiId") String djiId);
}

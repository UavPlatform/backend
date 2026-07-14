package com.uav.uav.mapper;

import com.uav.uav.pojo.entity.UavGpsRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GpsRecordRepository extends JpaRepository<UavGpsRecord, Long> {

    /** 按订单号查询轨迹，时间升序 */
    List<UavGpsRecord> findByOrderNumOrderByTimestampAsc(String orderNum);

    /** 查询指定无人机在指定时间之后的轨迹 */
    List<UavGpsRecord> findByUavIdAndTimestampAfterOrderByTimestampAsc(Long uavId, long timestamp);

    /** 查询指定设备最近一条记录 */
    UavGpsRecord findFirstByDeviceIdOrderByTimestampDesc(String deviceId);
}

package com.drone.mapper;

import com.drone.pojo.entity.Uav;
import com.drone.pojo.vo.UavVo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;


public interface UavRepository extends JpaRepository<Uav, Long> {

    @Query("SELECT new com.drone.pojo.vo.UavVo(u.id, u.uavName) FROM Uav u WHERE u.uavName = :name")
    UavVo findUavByUavName(@Param("name") String uavName);

    @Query("SELECT MAX(u.id) FROM Uav u")
    Long findMaxId();

    @Query("SELECT new com.drone.pojo.vo.UavVo(u.id, u.uavName) FROM Uav u")
    UavVo[] getAll();

    @Query("SELECT new com.drone.pojo.vo.UavVo(u.id, u.uavName) FROM Uav u WHERE u.onlineStatus = :status")
    UavVo[] findUavByOnlineStatus(@Param("status") Character status);

    Uav findByDjiId(String djiId);

    @Query("SELECT u FROM Uav u WHERE u.id = :id")
    Uav findUavById(@Param("id") Long id);

    @Query("UPDATE Uav u SET u.onlineStatus = :status WHERE u.id = :id")
    @Modifying
    @Transactional
    void updateUavOnlineStatus(@Param("id") Long id, @Param("status") Character status);
}

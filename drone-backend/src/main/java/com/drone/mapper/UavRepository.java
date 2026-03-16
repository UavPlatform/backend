package com.drone.mapper;

import com.drone.pojo.entity.Uav;
import com.drone.pojo.vo.UavVo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UavRepository extends JpaRepository<Uav, Long> {

    @Query("SELECT new com.drone.pojo.vo.UavVo(u.id, u.uavName) FROM Uav u WHERE u.uavName = :name")
    UavVo findUavByUavName(@Param("name") String uavName);

    @Query("SELECT MAX(u.id) FROM Uav u")
    Long findMaxId();

    @Query("SELECT new com.drone.pojo.vo.UavVo(u.id, u.uavName) FROM Uav u")
    UavVo[] getAll();
}

package com.drone.mapper;

import com.drone.pojo.entity.Uav;
import com.drone.pojo.vo.uav.UavVo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;


public interface UavRepository extends JpaRepository<Uav, Long> {

    @Query("SELECT new com.drone.pojo.vo.uav.UavVo(u.id, u.uavName, u.djiId, u.controllerModel, u.isAvailable) FROM Uav u WHERE u.uavName = :name")
    Optional<UavVo> findUavByUavName(@Param("name") String uavName);

    @Query("SELECT new com.drone.pojo.vo.uav.UavVo(u.id, u.uavName, u.djiId, u.controllerModel, u.isAvailable) FROM Uav u")
    List<UavVo> getAll();

    @Query("SELECT new com.drone.pojo.vo.uav.UavVo(u.id, u.uavName, u.djiId, u.controllerModel, u.isAvailable) FROM Uav u WHERE u.onlineStatus = :status")
    List<UavVo> findUavByOnlineStatus(@Param("status") Character status);

    Optional<Uav> findByDjiId(@Param("djiId") String djiId);

    @Modifying
    @Query("UPDATE Uav u SET u.onlineStatus = :status WHERE u.id = :id")
    void updateUavOnlineStatus(@Param("id") Long id, @Param("status") Character status);

    @Modifying
    @Query("UPDATE Uav u SET u.isAvailable = :isAvailable WHERE u.djiId = :djiId")
    int updateUavAvailableByDjiId(@Param("djiId") String djiId, @Param("isAvailable") Character isAvailable);

    @Query("SELECT u FROM Uav u")
    List<Uav> findAllUav();
}

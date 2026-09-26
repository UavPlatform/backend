package com.uav.aircraft.mapper;

import com.uav.aircraft.pojo.entity.AircraftModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AircraftModelRepository extends JpaRepository<AircraftModel, Long> {

    /** 机型目录（查询 API 返回启用机型；是否可吊运由 transport_enabled 单独标识）。 */
    List<AircraftModel> findByEnabledTrueOrderByModelCodeAsc();

    Optional<AircraftModel> findByModelCode(String modelCode);
}

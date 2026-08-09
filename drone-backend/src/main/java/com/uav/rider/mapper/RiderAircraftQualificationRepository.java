package com.uav.rider.mapper;

import com.uav.rider.pojo.entity.RiderAircraftQualification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiderAircraftQualificationRepository extends JpaRepository<RiderAircraftQualification, Long> {
    List<RiderAircraftQualification> findByUserId(Long userId);
}

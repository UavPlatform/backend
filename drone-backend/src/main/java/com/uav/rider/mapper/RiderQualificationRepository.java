package com.uav.rider.mapper;

import com.uav.rider.pojo.entity.RiderQualification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiderQualificationRepository extends JpaRepository<RiderQualification, Long> {

    List<RiderQualification> findByUserIdOrderByIdAsc(Long userId);
}

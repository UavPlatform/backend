package com.uav.equipment.mapper;
import com.uav.equipment.pojo.entity.DroneBorrow;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface DroneBorrowRepository extends JpaRepository<DroneBorrow, String> {
    boolean existsByDroneId(String droneId);
    Optional<DroneBorrow> findByPilotIdAndRequestKey(Long pilot, String requestKey);
    Page<DroneBorrow> findByPilotId(Long pilot, Pageable page);
    Page<DroneBorrow> findByDroneId(String droneId, Pageable page);
}

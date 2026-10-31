package com.uav.equipment.mapper;
import com.uav.equipment.pojo.entity.DroneBorrow;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DroneBorrowRepository extends JpaRepository<DroneBorrow, String> {
    boolean existsByDroneId(String droneId);
    Optional<DroneBorrow> findByPilotIdAndRequestKey(Long pilot, String requestKey);
    Page<DroneBorrow> findByPilotId(Long pilot, Pageable page);
    Page<DroneBorrow> findByDroneId(String droneId, Pageable page);
    long countByPilotIdAndStatus(Long pilotId, String status);
    List<DroneBorrow> findByPilotIdAndStatusOrderByBorrowedAtDesc(Long pilotId, String status);

    @Query("select b from DroneBorrow b where (:status is null or b.status = :status)")
    Page<DroneBorrow> adminSearch(@Param("status") String status, Pageable page);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from DroneBorrow b where b.id = :id")
    Optional<DroneBorrow> lockById(@Param("id") String id);
}

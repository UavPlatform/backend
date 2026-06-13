package com.uav.order.mapper;

import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<MissionOrder, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM MissionOrder o WHERE o.userId = :userId AND o.orderStatus = :orderStatus")
    Optional<MissionOrder> findByUserIdAndOrderStatusForUpdate(@Param("userId") Long userId,
                                                                @Param("orderStatus") OrderStatus orderStatus);

    @EntityGraph(attributePaths = "task")
    Optional<MissionOrder> findByOrderNum(String orderNum);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM MissionOrder o WHERE o.orderNum = :orderNum")
    Optional<MissionOrder> findByOrderNumForUpdate(@Param("orderNum") String orderNum);

    @EntityGraph(attributePaths = "task")
    Page<MissionOrder> findByUserIdOrderByCreateTimeDesc(Long userId, Pageable pageable);

    Optional<MissionOrder> findByTaskId(Long taskId);
}

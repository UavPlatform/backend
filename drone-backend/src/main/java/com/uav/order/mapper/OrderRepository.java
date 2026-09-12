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
import org.springframework.data.jpa.repository.Modifying;

import java.time.LocalDateTime;
import java.util.List;
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

    /** 1B-9a：超时未验收的订单（WAITING_CONFIRM 且 update_time 早于阈值） */
    @Query("SELECT o FROM MissionOrder o WHERE o.orderStatus = :status AND o.updateTime <= :cutoff")
    List<MissionOrder> findByOrderStatusAndUpdateTimeBefore(@Param("status") OrderStatus status,
                                                            @Param("cutoff") LocalDateTime cutoff);

    /**
     * 1B-9a 测试/运维辅助：直改 update_time（绕过 @PreUpdate 的 now 覆盖），模拟验收超时时间。
     * clearAutomatically：更新后清空持久化上下文，避免一级缓存读到旧值。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MissionOrder o SET o.updateTime = :time WHERE o.id = :id")
    int forceUpdateTime(@Param("id") Long id, @Param("time") LocalDateTime time);
}

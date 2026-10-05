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

    @EntityGraph(attributePaths = "task")
    Page<MissionOrder> findByUserIdAndOrderStatusOrderByCreateTimeDesc(
            Long userId, OrderStatus orderStatus, Pageable pageable);

    // ── App「我的交易」：我买到的 ∪ 我卖出的 ──
    // 卖方的权威口径是订单上的成交指针 selectedApplicationId（选定应征时写入，重新选定会改指），
    // 而不是履约记录 task_assignment（取消接单会被删除，无法表达「成交」）。

    @EntityGraph(attributePaths = "task")
    @Query("SELECT o FROM MissionOrder o WHERE o.selectedApplicationId IN "
            + "(SELECT a.id FROM TaskApplication a WHERE a.riderId = :riderId) "
            + "ORDER BY o.createTime DESC")
    Page<MissionOrder> findSoldOrders(@Param("riderId") Long riderId, Pageable pageable);

    @EntityGraph(attributePaths = "task")
    @Query("SELECT o FROM MissionOrder o WHERE o.orderStatus = :orderStatus AND o.selectedApplicationId IN "
            + "(SELECT a.id FROM TaskApplication a WHERE a.riderId = :riderId) "
            + "ORDER BY o.createTime DESC")
    Page<MissionOrder> findSoldOrdersByStatus(@Param("riderId") Long riderId,
                                              @Param("orderStatus") OrderStatus orderStatus,
                                              Pageable pageable);

    @EntityGraph(attributePaths = "task")
    @Query("SELECT o FROM MissionOrder o WHERE "
            + "(o.userId = :userId OR o.selectedApplicationId IN "
            + " (SELECT a.id FROM TaskApplication a WHERE a.riderId = :userId)) "
            + "ORDER BY o.createTime DESC")
    Page<MissionOrder> findMyTrades(@Param("userId") Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "task")
    @Query("SELECT o FROM MissionOrder o WHERE o.orderStatus = :orderStatus AND "
            + "(o.userId = :userId OR o.selectedApplicationId IN "
            + " (SELECT a.id FROM TaskApplication a WHERE a.riderId = :userId)) "
            + "ORDER BY o.createTime DESC")
    Page<MissionOrder> findMyTradesByStatus(@Param("userId") Long userId,
                                            @Param("orderStatus") OrderStatus orderStatus,
                                            Pageable pageable);

    /** 待评价：仅「已完成」可评价（{@code OrderReviewServiceImpl} 硬校验），且尚未提交过评价。 */
    @EntityGraph(attributePaths = "task")
    @Query("SELECT o FROM MissionOrder o WHERE o.userId = :userId AND o.orderStatus = :orderStatus "
            + "AND NOT EXISTS (SELECT r.id FROM OrderReview r WHERE r.orderNum = o.orderNum) "
            + "ORDER BY o.createTime DESC")
    Page<MissionOrder> findPendingReviewOrders(@Param("userId") Long userId,
                                               @Param("orderStatus") OrderStatus orderStatus,
                                               Pageable pageable);

    // ── 统计计数（一次 count，不在内存过滤）──

    long countByUserId(Long userId);

    @Query("SELECT COUNT(o) FROM MissionOrder o WHERE o.selectedApplicationId IN "
            + "(SELECT a.id FROM TaskApplication a WHERE a.riderId = :riderId)")
    long countSoldOrders(@Param("riderId") Long riderId);

    @Query("SELECT COUNT(o) FROM MissionOrder o WHERE "
            + "o.userId = :userId OR o.selectedApplicationId IN "
            + "(SELECT a.id FROM TaskApplication a WHERE a.riderId = :userId)")
    long countMyTrades(@Param("userId") Long userId);

    @Query("SELECT COUNT(o) FROM MissionOrder o WHERE o.orderStatus = :orderStatus AND "
            + "(o.userId = :userId OR o.selectedApplicationId IN "
            + " (SELECT a.id FROM TaskApplication a WHERE a.riderId = :userId))")
    long countMyTradesByStatus(@Param("userId") Long userId, @Param("orderStatus") OrderStatus orderStatus);

    @Query("SELECT COUNT(o) FROM MissionOrder o WHERE o.userId = :userId AND o.orderStatus = :orderStatus "
            + "AND NOT EXISTS (SELECT r.id FROM OrderReview r WHERE r.orderNum = o.orderNum)")
    long countPendingReviewOrders(@Param("userId") Long userId, @Param("orderStatus") OrderStatus orderStatus);

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

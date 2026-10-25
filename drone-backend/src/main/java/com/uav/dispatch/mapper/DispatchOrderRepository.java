package com.uav.dispatch.mapper;

import com.uav.dispatch.pojo.entity.DispatchOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DispatchOrderRepository extends JpaRepository<DispatchOrder, String> {
    Optional<DispatchOrder> findByUserIdAndRequestKey(Long userId, String requestKey);
    List<DispatchOrder> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
    Page<DispatchOrder> findByStageAndFundsStatusOrderByCreatedAtAsc(String stage, String fundsStatus, Pageable page);
    List<DispatchOrder> findTop50ByAssignedPilotIdOrderByUpdatedAtDesc(Long pilotId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DispatchOrder o where o.id = :id")
    Optional<DispatchOrder> lockOrder(@Param("id") String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DispatchOrder o where o.id = :id and o.userId = :owner")
    Optional<DispatchOrder> lockOwned(@Param("id") String id, @Param("owner") Long userId);
    /** Admin supervision list (REQ-SUP-MON): optional filters, newest first via Pageable sort. */
    @Query(value = "select o from DispatchOrder o where (:stage is null or o.stage = :stage)"
            + " and (:funds is null or o.fundsStatus = :funds)"
            + " and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to)"
            + " and (:kw is null or o.id like :kw escape '\\'"
            + " or o.userId in (select u.id from User u where u.userName like :kw escape '\\')"
            + " or o.assignedPilotId in (select u.id from User u where u.userName like :kw escape '\\'))",
            countQuery = "select count(o) from DispatchOrder o where (:stage is null or o.stage = :stage)"
            + " and (:funds is null or o.fundsStatus = :funds)"
            + " and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to)"
            + " and (:kw is null or o.id like :kw escape '\\'"
            + " or o.userId in (select u.id from User u where u.userName like :kw escape '\\')"
            + " or o.assignedPilotId in (select u.id from User u where u.userName like :kw escape '\\'))")
    Page<DispatchOrder> adminSearch(@Param("stage") String stage, @Param("funds") String fundsStatus,
            @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to,
            @Param("kw") String keyword, Pageable page);
}

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
    /** REQ-SUP-GOV-005: latest order flown with this drone (djifly-reported serial; indexed in V22). */
    @Query("select o from DispatchOrder o where o.executedSerialNumber in :serials and not exists (select 1 from DispatchOrder n"
            + " where n.executedSerialNumber = o.executedSerialNumber and (n.createdAt > o.createdAt or (n.createdAt = o.createdAt and n.id > o.id)))")
    List<DispatchOrder> latestByExecutedSerials(@Param("serials") java.util.Collection<String> serials);
    @Query("select distinct o.executedSerialNumber from DispatchOrder o where o.executedSerialNumber in :serials and o.stage in :stages")
    List<String> executingSerials(@Param("serials") java.util.Collection<String> serials, @Param("stages") java.util.Collection<String> stages);
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

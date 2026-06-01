package com.drone.mapper;

import com.drone.pojo.entity.MissionOrder;
import com.drone.pojo.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<MissionOrder, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM MissionOrder o WHERE o.userName = :userName AND o.orderStatus = :orderStatus")
    Optional<MissionOrder> findByUserNameAndOrderStatusForUpdate(@Param("userName") String userName,
                                                                  @Param("orderStatus") OrderStatus orderStatus);

    @EntityGraph(attributePaths = "route")
    Optional<MissionOrder> findByOrderNum(String orderNum);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM MissionOrder o WHERE o.orderNum = :orderNum")
    Optional<MissionOrder> findByOrderNumForUpdate(@Param("orderNum") String orderNum);

    @EntityGraph(attributePaths = "route")
    List<MissionOrder> findByUserNameOrderByCreateTimeDesc(String userName);

    @EntityGraph(attributePaths = "route")
    Page<MissionOrder> findByUserNameOrderByCreateTimeDesc(String userName, Pageable pageable);
}

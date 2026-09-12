package com.uav.task.mapper;

import com.uav.task.pojo.entity.Task;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
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
import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    @EntityGraph(attributePaths = "waypoints")
    List<Task> findAllById(Iterable<Long> ids);

    @EntityGraph(attributePaths = "waypoints")
    List<Task> findByUserIdOrderByCreateTimeDesc(Long userId);

    @EntityGraph(attributePaths = "waypoints")
    Page<Task> findByUserIdOrderByCreateTimeDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "waypoints")
    Optional<Task> findByTaskNum(String taskNum);

    @EntityGraph(attributePaths = "waypoints")
    List<Task> findByTaskStatusOrderByCreateTimeDesc(TaskStatus taskStatus);

    /**
     * 接单大厅可见任务（1B-2a，裁决 Q1=A 托管式支付）：仅返回「空闲 且 关联订单已支付」的任务。
     * 未支付任务对飞手不可见。
     */
    @EntityGraph(attributePaths = "waypoints")
    @Query("SELECT t FROM Task t WHERE t.taskStatus = :idle AND EXISTS "
            + "(SELECT o FROM MissionOrder o WHERE o.task = t AND o.orderStatus = :paid) "
            + "ORDER BY t.createTime DESC")
    List<Task> findPaidIdleTasks(@Param("idle") TaskStatus idle, @Param("paid") OrderStatus paid);

    @EntityGraph(attributePaths = "waypoints")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.taskNum = :taskNum")
    Optional<Task> findByTaskNumForUpdate(@Param("taskNum") String taskNum);
}

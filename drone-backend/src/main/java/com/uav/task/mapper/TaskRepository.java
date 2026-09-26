package com.uav.task.mapper;

import com.uav.task.pojo.entity.Task;
import com.uav.server.enums.MatchStatus;
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
     * 任务广场可见任务（TASK-BACKEND-004 / ADR-0003 闲鱼式撮合）：返回「空闲 且 撮合开放」的任务
     * （SEEKING_RIDER 招募中 / NEGOTIATING 洽谈中）。不再要求订单已支付——支付发生在用户选定应征之后。
     */
    @EntityGraph(attributePaths = "waypoints")
    @Query("SELECT t FROM Task t WHERE t.taskStatus = :idle AND t.matchStatus IN :matching "
            + "ORDER BY t.createTime DESC")
    List<Task> findMatchingTasks(@Param("idle") TaskStatus idle,
                                  @Param("matching") List<MatchStatus> matching);

    @EntityGraph(attributePaths = "waypoints")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.taskNum = :taskNum")
    Optional<Task> findByTaskNumForUpdate(@Param("taskNum") String taskNum);
}

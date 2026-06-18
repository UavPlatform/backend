package com.uav.task.mapper;

import com.uav.task.pojo.entity.Task;
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

    @EntityGraph(attributePaths = "waypoints")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.taskNum = :taskNum")
    Optional<Task> findByTaskNumForUpdate(@Param("taskNum") String taskNum);
}

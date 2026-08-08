package com.uav.task.mapper;

import com.uav.task.pojo.entity.TaskAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, Long> {

    Optional<TaskAssignment> findByTaskId(Long taskId);

    List<TaskAssignment> findByRiderIdAndCompleteTimeIsNullOrderByAcceptTimeDesc(Long riderId);

    List<TaskAssignment> findByRiderIdOrderByAcceptTimeDesc(Long riderId);

    /** 已完成任务数 */
    long countByRiderIdAndCompleteTimeIsNotNull(Long riderId);

    /** 今日接单数 */
    long countByRiderIdAndAcceptTimeAfter(Long riderId, LocalDateTime since);

    /** 骑手完成任务的总收益（join task 表取 reward） */
    @Query("SELECT COALESCE(SUM(t.reward), 0) FROM TaskAssignment a JOIN Task t ON a.taskId = t.id " +
           "WHERE a.riderId = :riderId AND a.completeTime IS NOT NULL")
    Double sumRewardByRiderId(@Param("riderId") Long riderId);
}

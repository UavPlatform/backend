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

    /**
     * 批量获取多名骑手的统计数据，一次查询替代 N*3 次。
     * 返回 Object[]{riderId, todayOrders, totalCompleted, totalEarnings}
     */
    @Query("SELECT a.riderId," +
           " COUNT(CASE WHEN a.acceptTime >= :todayStart THEN 1 END)," +
           " COUNT(CASE WHEN a.completeTime IS NOT NULL THEN 1 END)," +
           " COALESCE(SUM(CASE WHEN a.completeTime IS NOT NULL THEN t.reward ELSE 0 END), 0) " +
           "FROM TaskAssignment a JOIN Task t ON a.taskId = t.id " +
           "WHERE a.riderId IN :riderIds " +
           "GROUP BY a.riderId")
    List<Object[]> batchRiderStats(@Param("riderIds") List<Long> riderIds,
                                    @Param("todayStart") LocalDateTime todayStart);
}

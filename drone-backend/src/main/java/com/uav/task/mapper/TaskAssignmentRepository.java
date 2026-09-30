package com.uav.task.mapper;

import com.uav.task.pojo.entity.TaskAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
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
     * 批量飞手统计（{@code GET /rider/recommended}），一次 GROUP BY 替代每飞手 3 次查询（N×3）。
     *
     * <p>口径与单飞手 {@code TaskServiceImpl#getRiderStats} 逐项一致：
     * <ul>
     *   <li>今日接单：{@code acceptTime > todayStart}（同 {@code countByRiderIdAndAcceptTimeAfter}，严格大于）；</li>
     *   <li>累计完成：{@code completeTime IS NOT NULL}（同 {@code countByRiderIdAndCompleteTimeIsNotNull}）；</li>
     *   <li>累计收益：已完成记录关联任务的 {@code reward} 之和（同 {@code sumRewardByRiderId}）。</li>
     * </ul>
     * 用 LEFT JOIN 使计数不受任务缺失影响（单飞手计数查询本就不 join task）。
     * 返回 {@code Object[]{riderId, todayOrders, totalCompleted, totalEarnings}}；无接单记录的飞手不出现在结果中。
     */
    @Query("SELECT a.riderId, "
           + "SUM(CASE WHEN a.acceptTime > :todayStart THEN 1 ELSE 0 END), "
           + "SUM(CASE WHEN a.completeTime IS NOT NULL THEN 1 ELSE 0 END), "
           + "COALESCE(SUM(CASE WHEN a.completeTime IS NOT NULL THEN t.reward ELSE 0 END), 0) "
           + "FROM TaskAssignment a LEFT JOIN Task t ON a.taskId = t.id "
           + "WHERE a.riderId IN :riderIds "
           + "GROUP BY a.riderId")
    List<Object[]> batchRiderStats(@Param("riderIds") Collection<Long> riderIds,
                                   @Param("todayStart") LocalDateTime todayStart);
}

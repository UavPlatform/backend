package com.uav.task.mapper;

import com.uav.task.pojo.entity.TaskApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskApplicationRepository extends JpaRepository<TaskApplication, Long> {

    /** 同一飞手同一任务仅一条应征（uk_task_application_task_rider）；重复应征走更新。 */
    Optional<TaskApplication> findByTaskIdAndRiderId(Long taskId, Long riderId);

    /** 任务的应征列表（按应征时间正序）。 */
    List<TaskApplication> findByTaskIdOrderByCreateTimeAsc(Long taskId);
}

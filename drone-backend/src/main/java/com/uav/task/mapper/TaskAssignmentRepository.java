package com.uav.task.mapper;

import com.uav.task.pojo.entity.TaskAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, Long> {

    Optional<TaskAssignment> findByTaskId(Long taskId);

    List<TaskAssignment> findByRiderIdAndCompleteTimeIsNullOrderByAcceptTimeDesc(Long riderId);

    List<TaskAssignment> findByRiderIdOrderByAcceptTimeDesc(Long riderId);
}

package com.uav.dispatch.mapper;

import com.uav.dispatch.pojo.entity.DispatchAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DispatchAssignmentRepository extends JpaRepository<DispatchAssignment, Long> {
    java.util.List<DispatchAssignment> findByOrderIdOrderByAssignmentVersionAsc(String orderId);
    Optional<DispatchAssignment> findByOrderIdAndAssignmentVersion(String orderId, long version);
}

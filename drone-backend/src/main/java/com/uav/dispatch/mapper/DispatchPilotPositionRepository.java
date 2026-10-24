package com.uav.dispatch.mapper;
import com.uav.dispatch.pojo.entity.DispatchPilotPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface DispatchPilotPositionRepository extends JpaRepository<DispatchPilotPosition, Long> {
    @Query("select p from DispatchPilotPosition p, User u where p.pilotId = u.id and u.role = 1 and u.status = 1 and p.expiresAt > :now")
    List<DispatchPilotPosition> eligible(@Param("now") long now);
    @Query("select p from DispatchPilotPosition p, User u where p.pilotId = :pilot and p.pilotId = u.id and u.role = 1 and u.status = 1 and p.expiresAt > :now")
    java.util.Optional<DispatchPilotPosition> freshForPilot(@Param("pilot") Long pilot, @Param("now") long now);
}

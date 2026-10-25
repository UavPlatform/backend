package com.uav.flight.mapper;
import com.uav.flight.pojo.entity.DispatchLiveSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DispatchLiveRepository extends JpaRepository<DispatchLiveSession, String> {
    Optional<DispatchLiveSession> findByPilotIdAndRequestKey(Long pilot, String key);
    Optional<DispatchLiveSession> findByActiveConnectionId(String connection);
}

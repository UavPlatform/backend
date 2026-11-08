package com.uav.flight.mapper;
import com.uav.flight.pojo.entity.FlightConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface FlightConnectionRepository extends JpaRepository<FlightConnection, String> {
 java.util.List<FlightConnection> findByOrderIdOrderByOpenedAtAscIdAsc(String order);
 Optional<FlightConnection> findByActiveOrderId(String order);
 Optional<FlightConnection> findFirstByPilotIdAndActiveSerialNotNullOrderByOpenedAtDesc(Long pilotId);
 /** REQ-SUP-GOV-006: block delete when any flight connection referenced this model. */
 boolean existsByAircraftModelId(Long aircraftModelId);
}

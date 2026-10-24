package com.uav.flight.mapper;
import com.uav.flight.pojo.entity.FlightSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
import java.util.Optional;
public interface FlightSampleRepository extends JpaRepository<FlightSample, Long> {
 Optional<FlightSample> findByConnectionIdAndSequenceNumber(String connection, long sequence);
 @org.springframework.data.jpa.repository.Query("select s from FlightSample s, FlightConnection c where s.connectionId = c.id and s.orderId = :order and s.latitude is not null order by c.openedAt desc, s.sequenceNumber desc")
 java.util.List<FlightSample> lastPositions(@org.springframework.data.repository.query.Param("order") String order, Pageable page);
 Page<FlightSample> findByOrderIdAndIdGreaterThanOrderByIdAsc(String order, long after, Pageable page);
}

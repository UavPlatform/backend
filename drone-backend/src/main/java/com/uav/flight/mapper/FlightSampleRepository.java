package com.uav.flight.mapper;
import com.uav.flight.pojo.entity.FlightSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.util.List;
import java.util.Optional;
public interface FlightSampleRepository extends JpaRepository<FlightSample, Long> {
 Optional<FlightSample> findByConnectionIdAndSequenceNumber(String connection, long sequence);
 Optional<FlightSample> findBySerialNumberAndCapturedAt(String serial, long capturedAt);
 @Query("select s from FlightSample s, FlightConnection c where s.connectionId = c.id and s.orderId = :order and s.latitude is not null order by c.openedAt desc, s.sequenceNumber desc")
 List<FlightSample> lastPositions(@Param("order") String order, Pageable page);
 Page<FlightSample> findByOrderIdAndIdGreaterThanOrderByIdAsc(String order, long after, Pageable page);
 @Query("select s from FlightSample s where s.orderId = :order and s.capturedAt >= :from and s.capturedAt <= :to"
         + " and (s.capturedAt > :afterAt or (s.capturedAt = :afterAt and s.id > :afterId)) order by s.capturedAt asc, s.id asc")
 List<FlightSample> history(@Param("order") String order, @Param("from") long from, @Param("to") long to,
         @Param("afterAt") long afterAt, @Param("afterId") long afterId, Pageable page);
}

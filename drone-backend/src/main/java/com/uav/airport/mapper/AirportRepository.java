package com.uav.airport.mapper;

import com.uav.airport.pojo.entity.Airport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AirportRepository extends JpaRepository<Airport, Long> {
    Page<Airport> findAllByOrderByIdAsc(Pageable pageable);
}

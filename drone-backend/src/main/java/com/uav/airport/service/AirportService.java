package com.uav.airport.service;

import com.uav.airport.mapper.AirportRepository;
import com.uav.airport.pojo.entity.Airport;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0012: airports exist as rows; registration always binds the seeded default.
 * No admin airport CRUD in this version (李家骏 2026-10-27).
 */
@Service
public class AirportService {
    /** Flyway V23 seed id. */
    public static final long DEFAULT_AIRPORT_ID = 1L;

    private final AirportRepository airports;

    public AirportService(AirportRepository airports) {
        this.airports = airports;
    }

    @Transactional(readOnly = true)
    public Airport requireDefault() {
        return airports.findById(DEFAULT_AIRPORT_ID).orElseThrow(() ->
                new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INVALID_PARAM, "默认机场未初始化"));
    }

    @Transactional(readOnly = true)
    public Airport require(Long id) {
        return airports.findById(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.NO_PERMISSION, "记录不存在或不可访问"));
    }
}

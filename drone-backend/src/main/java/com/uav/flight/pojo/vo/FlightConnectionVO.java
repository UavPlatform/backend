package com.uav.flight.pojo.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record FlightConnectionVO(String connectionId, String orderId, long assignmentVersion, String serialNumber,
 String modelCode, String modelName, Long aircraftModelId, BigDecimal maxPayloadKg, boolean overweight,
 LocalDateTime openedAt, LocalDateTime endedAt, String endReason) { }

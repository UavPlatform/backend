package com.uav.flight.pojo.vo;
import java.time.LocalDateTime;
public record FlightSampleVO(Long sampleId, String connectionId, long sequence, long capturedAt, LocalDateTime receivedAt,
 Double latitude, Double longitude, Double altitude, Double speed, Integer battery, Boolean flying, Double payloadKg) { }

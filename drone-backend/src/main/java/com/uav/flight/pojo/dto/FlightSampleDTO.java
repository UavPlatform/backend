package com.uav.flight.pojo.dto;
import jakarta.validation.constraints.*;
public record FlightSampleDTO(@PositiveOrZero long sequence, @Positive long capturedAt,
 @DecimalMin("-90") @DecimalMax("90") Double latitude, @DecimalMin("-180") @DecimalMax("180") Double longitude,
 Double altitude, @PositiveOrZero Double speed, @Min(0) @Max(100) Integer battery, Boolean flying,
 @DecimalMin("0") @DecimalMax("10000") Double payloadKg) {
    /** Pre-payload constructor kept for callers that have no payload sensor. */
    public FlightSampleDTO(long sequence, long capturedAt, Double latitude, Double longitude, Double altitude, Double speed, Integer battery, Boolean flying) {
        this(sequence, capturedAt, latitude, longitude, altitude, speed, battery, flying, null);
    }
}

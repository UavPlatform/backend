package com.uav.flight.pojo.dto;
import jakarta.validation.constraints.*;
public record FlightSampleDTO(@PositiveOrZero long sequence, @Positive long capturedAt,
 @DecimalMin("-90") @DecimalMax("90") Double latitude, @DecimalMin("-180") @DecimalMax("180") Double longitude,
 Double altitude, @PositiveOrZero Double speed, @Min(0) @Max(100) Integer battery, Boolean flying) { }

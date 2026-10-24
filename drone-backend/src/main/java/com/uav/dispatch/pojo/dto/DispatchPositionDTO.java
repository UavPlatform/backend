package com.uav.dispatch.pojo.dto;
import jakarta.validation.constraints.*;
public record DispatchPositionDTO(
        @NotBlank @Size(max = 36) String sessionKey,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull @DecimalMin("0") Double accuracyMeters,
        @NotNull @Positive Long capturedAt,
        @NotNull @Pattern(regexp = "GCJ02") String coordinateSystem,
        @NotNull @AssertFalse Boolean mocked) { }

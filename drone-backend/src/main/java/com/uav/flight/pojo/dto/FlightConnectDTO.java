package com.uav.flight.pojo.dto;
import jakarta.validation.constraints.*;
public record FlightConnectDTO(@Positive long assignmentVersion, @NotBlank @Size(max = 64) String requestKey,
 @NotBlank @Size(max = 64) String serialNumber, @NotBlank @Size(max = 128) String modelCode, @NotBlank @Size(max = 128) String modelName) { }

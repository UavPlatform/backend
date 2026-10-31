package com.uav.equipment.pojo.dto;
import jakarta.validation.constraints.*;
/** Pilot apply: model only; optional order reference; idempotent requestKey. */
public record DroneBorrowDTO(
        @NotNull Long aircraftModelId,
        @Size(max = 36) String orderId,
        @NotBlank @Size(max = 64) String requestKey) { }

package com.uav.equipment.pojo.dto;
import jakarta.validation.constraints.*;
public record DroneBorrowDTO(@NotBlank @Size(max = 36) String droneId, @NotBlank @Size(max = 64) String requestKey) { }

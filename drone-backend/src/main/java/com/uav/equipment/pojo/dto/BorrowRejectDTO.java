package com.uav.equipment.pojo.dto;
import jakarta.validation.constraints.*;
public record BorrowRejectDTO(@NotBlank @Size(max = 500) String reason) { }

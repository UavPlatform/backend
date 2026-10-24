package com.uav.flight.pojo.dto;
import jakarta.validation.constraints.*;
public record DispatchLiveStartDTO(@NotBlank @Size(max = 64) String requestKey) { }

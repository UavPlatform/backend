package com.uav.flight.pojo.dto;
import jakarta.validation.constraints.*;
public record DispatchLiveHeartbeatDTO(@NotBlank @Pattern(regexp = "STARTING|RUNNING") String state) { }

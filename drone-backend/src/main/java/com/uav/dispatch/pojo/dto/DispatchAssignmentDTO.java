package com.uav.dispatch.pojo.dto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DispatchAssignmentDTO(@NotNull @Positive Long assignmentVersion) { }

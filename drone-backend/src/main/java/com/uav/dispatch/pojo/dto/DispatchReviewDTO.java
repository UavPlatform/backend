package com.uav.dispatch.pojo.dto;
import jakarta.validation.constraints.*;

public record DispatchReviewDTO(@NotNull @Min(1) @Max(5) Integer score, @Size(max = 500) String comment) { }

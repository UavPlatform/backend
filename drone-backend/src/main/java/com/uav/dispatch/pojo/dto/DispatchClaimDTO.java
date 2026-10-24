package com.uav.dispatch.pojo.dto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record DispatchClaimDTO(@NotNull @PositiveOrZero Long offerRevision) { }

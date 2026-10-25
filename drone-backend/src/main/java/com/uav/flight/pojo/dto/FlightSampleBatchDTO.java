package com.uav.flight.pojo.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
/** Offline backfill or periodic batch: up to 500 SDK samples of one connection (ADR-0009). */
public record FlightSampleBatchDTO(@NotEmpty @Size(max = 500) List<@Valid @NotNull FlightSampleDTO> samples) { }

package com.uav.dispatch.pojo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Partial text inputs for a draft, never an order or authoritative price/state. */
public record WaybillDraftDTO(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull @Min(0) @Max(4) Integer step,
        @NotNull @Size(max = 40) Map<String, @NotNull @Size(max = 2000) String> fields,
        @PositiveOrZero Long expectedRevision) {
}

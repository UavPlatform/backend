package com.uav.dispatch.pojo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record DispatchOrderDTO(@NotBlank @Size(max = 36) String assessmentId,
                               @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") String requestKey,
                               @NotNull @AssertTrue Boolean declarationAccepted,
                               @NotNull @Valid WaybillDraftDTO waybill) { }

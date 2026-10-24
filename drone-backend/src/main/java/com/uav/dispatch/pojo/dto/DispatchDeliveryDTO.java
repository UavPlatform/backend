package com.uav.dispatch.pojo.dto;
import jakarta.validation.constraints.*;
import java.util.List;

public record DispatchDeliveryDTO(@NotNull @Positive Long assignmentVersion,
        @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 36) String> materialIds) { }

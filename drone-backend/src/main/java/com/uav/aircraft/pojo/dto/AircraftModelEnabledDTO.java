package com.uav.aircraft.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "机型启用/停用")
public record AircraftModelEnabledDTO(
        @Schema(description = "true=启用，false=停用；停用后登记无人机时不可选")
        @NotNull Boolean enabled) {
}

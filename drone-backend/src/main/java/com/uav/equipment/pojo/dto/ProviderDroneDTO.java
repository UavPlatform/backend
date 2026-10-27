package com.uav.equipment.pojo.dto;
import jakarta.validation.constraints.*;
/** ADR-0012: register/update drone; airport is the seeded default (not chosen by admin). */
public record ProviderDroneDTO(@NotNull @Positive Long aircraftModelId, @NotBlank @Size(max = 128) String serialNumber) { }

package com.uav.equipment.pojo.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record ProviderDroneDTO(@NotNull @Positive Long aircraftModelId, @NotBlank @Size(max = 128) String serialNumber,
        @NotBlank @Size(max = 100) String airportName,
        @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude) { }

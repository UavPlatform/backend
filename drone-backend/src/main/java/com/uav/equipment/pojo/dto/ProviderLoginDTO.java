package com.uav.equipment.pojo.dto;
import jakarta.validation.constraints.*;
public record ProviderLoginDTO(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password) { }

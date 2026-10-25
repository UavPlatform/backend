package com.uav.dispatch.pojo.dto;
import jakarta.validation.constraints.*;

public record DispatchMaterialUploadDTO(@NotBlank @Pattern(regexp = "CARGO|START_SITE|END_SITE|PILOT_DELIVERY|SHIPPER_RECEIPT") String purpose,
        @NotBlank @Size(max = 255) String fileName, @NotBlank @Size(max = 64) String contentType,
        @NotNull @Positive Long sizeBytes) { }

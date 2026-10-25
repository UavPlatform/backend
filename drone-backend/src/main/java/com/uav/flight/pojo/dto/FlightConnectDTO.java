package com.uav.flight.pojo.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
/** {@code startedAt}: device time (epoch ms) when djifly recorded the connection locally, e.g. while offline (ADR-0009). */
public record FlightConnectDTO(@Positive long assignmentVersion, @NotBlank @Size(max = 64) String requestKey,
 @NotBlank @Size(max = 64) String serialNumber, @NotBlank @Size(max = 128) String modelCode, @NotBlank @Size(max = 128) String modelName,
 @Schema(description = "设备本地记录连机的时间（毫秒）；离线先记录、联网后补报时填写，服务端限制在最近 24 小时内") @Positive Long startedAt) {
    public FlightConnectDTO(long assignmentVersion, String requestKey, String serialNumber, String modelCode, String modelName) {
        this(assignmentVersion, requestKey, serialNumber, modelCode, modelName, null);
    }
}

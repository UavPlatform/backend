package com.uav.rider.pojo.dto;

import com.uav.server.enums.LicenseGrade;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 驾驶资质条目（{@code PATCH /rider/info} 的 {@code qualifications} 元素）。
 */
@Data
@Schema(description = "驾驶资质条目：机型（取自 /api/aircraft-models）× 执照等级")
public class RiderQualificationDto {

    @Schema(description = "机型ID（aircraft_model.id，须为启用机型）", example = "1")
    @NotNull(message = "机型不能为空")
    private Long aircraftModelId;

    @Schema(description = "执照等级：VLOS 视距内 / BVLOS 超视距 / TEACHER 教员", example = "VLOS")
    @NotNull(message = "执照等级不能为空")
    private LicenseGrade licenseGrade;
}

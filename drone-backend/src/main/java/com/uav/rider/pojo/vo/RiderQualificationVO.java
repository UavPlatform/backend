package com.uav.rider.pojo.vo;

import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.rider.pojo.entity.RiderQualification;
import com.uav.server.enums.LicenseGrade;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 飞手驾驶资质条目（飞手本人 {@code GET/PATCH /rider/info} 与监管端 {@code GET /admin/pilots/{userId}} 共用）。
 */
@Schema(description = "飞手驾驶资质（机型 × 执照等级）")
public record RiderQualificationVO(
        @Schema(description = "资质ID")
        Long id,
        @Schema(description = "机型ID（aircraft_model.id）")
        Long aircraftModelId,
        @Schema(description = "型号编码（如 FC30）；机型缺失时为 null")
        String modelCode,
        @Schema(description = "机型显示名（如 DJI FlyCart 30）；机型缺失时为 null")
        String modelName,
        @Schema(description = "执照等级枚举名")
        LicenseGrade licenseGrade,
        @Schema(description = "执照等级中文名（视距内/超视距/教员）")
        String licenseGradeLabel) {

    public static RiderQualificationVO of(RiderQualification q, AircraftModel model) {
        LicenseGrade grade = q.getLicenseGrade();
        return new RiderQualificationVO(q.getId(), q.getAircraftModelId(),
                model != null ? model.getModelCode() : null,
                model != null ? model.getDisplayName() : null,
                grade, grade != null ? grade.getLabel() : null);
    }
}

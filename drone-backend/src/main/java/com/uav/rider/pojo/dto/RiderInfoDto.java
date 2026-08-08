package com.uav.rider.pojo.dto;

import com.uav.server.enums.AircraftCategory;
import com.uav.server.enums.LicenseGrade;
import com.uav.server.enums.UavWeight;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class RiderInfoDto {
    @Min(value = 16, message = "年龄不能小于16岁")
    @Max(value = 100, message = "年龄不能大于100岁")
    private Integer age;

    @Size(max = 64, message = "证件编号不能超过64字符")
    private String certNumber;

    private LocalDate certValidFrom;
    private LocalDate certValidUntil;

    @Size(max = 500, message = "自我介绍不能超过500字符")
    private String selfIntroduction;

    private String location;

    private List<QualificationDto> qualifications;

    @Data
    public static class QualificationDto {
        @NotNull(message = "资质类别不能为空")
        private AircraftCategory category;

        @NotNull(message = "执照等级不能为空")
        private LicenseGrade license;

        @NotNull(message = "重量等级不能为空")
        private UavWeight weight;
    }
}

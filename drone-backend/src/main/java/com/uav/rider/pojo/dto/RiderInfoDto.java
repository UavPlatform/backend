package com.uav.rider.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDate;
import java.util.List;

/**
 * 飞手资料局部更新（{@code PATCH /rider/info}）：字段为 null 表示不修改。
 *
 * <p>{@code qualifications} 为资质全集语义：null 不修改；传数组则以其为准做集合差异
 * （新增/删除/改等级），空数组表示清空全部资质。
 */
@Data
@Schema(description = "飞手资料局部更新（字段为 null 表示不修改）")
public class RiderInfoDto {

    @Schema(description = "年龄（16~100）", example = "30")
    @Min(value = 16, message = "年龄不能小于16岁")
    @Max(value = 100, message = "年龄不能大于100岁")
    private Integer age;

    @Schema(description = "居民身份证号（18 位，末位可为 X；服务端校验格式与校验码，返回时一律脱敏）",
            example = "11010519491231002X")
    @ToString.Exclude
    private String idNumber;

    @Schema(description = "证件有效期截止日（yyyy-MM-dd，不得早于今天）", example = "2036-01-01")
    private LocalDate idExpiryDate;

    @Schema(description = "所在地", example = "广东省深圳市")
    @Size(max = 255, message = "所在地不能超过255字符")
    private String location;

    @Schema(description = "自我介绍", example = "五年吊运作业经验")
    @Size(max = 255, message = "自我介绍不能超过255字符")
    private String selfIntroduction;

    @Schema(description = "驾驶资质全集（null 不修改；[] 清空）；同一机型只能出现一次")
    @Size(max = 50, message = "资质条目不能超过50条")
    private List<@Valid @NotNull(message = "资质条目不能为空") RiderQualificationDto> qualifications;
}

package com.uav.rider.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 飞手本人资料（{@code GET/PATCH /rider/info}）。身份证号只返回脱敏值。
 */
@Schema(description = "飞手资料（身份证号脱敏）")
public record RiderInfoVO(
        @Schema(description = "飞手ID（user.id）")
        Long userId,
        @Schema(description = "用户名（昵称）")
        String userName,
        @Schema(description = "年龄；未填为 null")
        Integer age,
        @Schema(description = "脱敏身份证号（前 3 后 4，如 110***********002X）；未填为 null")
        String idNumberMasked,
        @Schema(description = "证件有效期截止日；未填为 null")
        LocalDate idExpiryDate,
        @Schema(description = "证件是否已过期（有效期早于今天）；未填有效期为 false")
        boolean idExpired,
        @Schema(description = "所在地")
        String location,
        @Schema(description = "自我介绍")
        String selfIntroduction,
        @Schema(description = "驾驶资质（无资质为空数组）")
        List<RiderQualificationVO> qualifications,
        @Schema(description = "资料创建时间；从未保存资料为 null")
        LocalDateTime createTime,
        @Schema(description = "资料最近更新时间；从未保存资料为 null")
        LocalDateTime updateTime) {
}

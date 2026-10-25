package com.uav.dispatch.service;

import com.uav.dispatch.pojo.dto.WaybillDraftDTO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

/** REQ-SHIP-PUB-002: drafts may be partial, submissions must be valid on the server. */
@Component
public class WaybillSubmissionValidator {
    public void validate(WaybillDraftDTO input) {
        Map<String, String> f = input.fields();
        if (input.schemaVersion() != 1 || !WaybillDraftService.allowedFields().containsAll(f.keySet())
                || f.values().stream().mapToInt(String::length).sum() > 16000) invalid("运单字段无效");
        if (!Set.of("普通", "易碎", "液体", "冷链", "生鲜").contains(value(f, "category"))) invalid("货物类别无效");
        range(f, "weightMin", "weightMax", false);
        for (String key : Set.of("length", "width", "height")) positive(f, key);
        try {
            if (Integer.parseInt(value(f, "count")) <= 0) invalid("件数须为正整数");
        } catch (NumberFormatException e) { invalid("件数须为正整数"); }
        required(f, "packaging");
        required(f, "lifting");
        if (!value(f, "declaredValue").isEmpty()) positive(f, "declaredValue");
        for (String side : Set.of("start", "end")) site(f, side);
        String mode = value(f, "timeMode");
        if (!Set.of("now", "scheduled").contains(mode)) invalid("出发时间无效");
        if (mode.equals("scheduled")) {
            try {
                String raw = value(f, "scheduledAt");
                var when = raw.matches(".*(Z|[+-][0-9]{2}:[0-9]{2})$")
                        ? OffsetDateTime.parse(raw).toInstant()
                        : LocalDateTime.parse(raw).toInstant(ZoneOffset.ofHours(8));
                if (!when.isAfter(java.time.Instant.now())) invalid("预约时间须在未来");
            } catch (java.time.DateTimeException e) { invalid("预约时间无效"); }
        }
        if (!Set.of("", "yes", "no").contains(value(f, "urgent"))) invalid("加急选项无效");
    }

    private void site(Map<String, String> f, String side) {
        for (String axis : Set.of("lat", "lng")) {
            BigDecimal n = decimal(f, side + "." + axis);
            int bound = axis.equals("lat") ? 90 : 180;
            if (n.abs().compareTo(BigDecimal.valueOf(bound)) > 0) invalid("地图坐标无效");
        }
        required(f, side + ".site");
        required(f, side + ".contact");
        if (!value(f, side + ".phone").replaceAll("[\\s()-]", "").matches("\\+?[0-9]{7,15}")) invalid("联系电话无效");
        String landing = value(f, side + ".landing");
        if (!Set.of("yes", "no").contains(landing)) invalid("请选择能否降落");
        if (landing.equals("no")) range(f, side + ".winchMin", side + ".winchMax", true);
    }

    private void range(Map<String, String> f, String min, String max, boolean requiredMax) {
        BigDecimal lower = positive(f, min);
        if (requiredMax || !value(f, max).isEmpty()) {
            if (positive(f, max).compareTo(lower) < 0) invalid("范围上限不能小于下限");
        }
    }

    private BigDecimal positive(Map<String, String> f, String key) {
        BigDecimal n = decimal(f, key);
        if (n.signum() <= 0 || n.compareTo(new BigDecimal("100000000")) > 0) invalid(key + "超出范围");
        return n;
    }

    private BigDecimal decimal(Map<String, String> f, String key) {
        try { return new BigDecimal(value(f, key)); }
        catch (NumberFormatException e) { throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                ApiErrorCode.INVALID_PARAM, key + "须为有效数字"); }
    }

    private void required(Map<String, String> f, String key) {
        if (value(f, key).isEmpty()) invalid("请填写" + key);
    }

    private String value(Map<String, String> f, String key) { return f.getOrDefault(key, "").trim(); }
    private void invalid(String message) {
        throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, message);
    }
}

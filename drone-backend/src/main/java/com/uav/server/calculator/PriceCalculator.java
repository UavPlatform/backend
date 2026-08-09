package com.uav.server.calculator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.uav.billing.service.BillConfigService;
import com.uav.server.enums.TaskType;
import com.uav.task.pojo.dto.WaypointDto;
import com.uav.task.pojo.entity.TaskWaypoint;
import com.uav.task.pojo.vo.PriceDetailVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台参考价计算器（MVP 口径，全部费率来自 bill_config 表，代码只留默认值兜底）：
 * total = 起步价 + 里程费(超基础里程部分) + 重量阶梯费 + 夜间附加费(前三项和 × nightRate)
 * 距离口径：航点折线距离（复用 RoutePriceCalculator 的 Haversine 算法）
 */
@Component
@Slf4j
public class PriceCalculator {

    private static final BigDecimal KM_TO_M = BigDecimal.valueOf(1000);

    /** 默认重量阶梯：5kg 内免费、15kg 内 10 元、30kg 内 25 元（可被 bill_config.weightSteps 覆盖） */
    private static final List<WeightStep> DEFAULT_WEIGHT_STEPS = List.of(
            new WeightStep(new BigDecimal("5"), BigDecimal.ZERO),
            new WeightStep(new BigDecimal("15"), new BigDecimal("10")),
            new WeightStep(new BigDecimal("30"), new BigDecimal("25")));

    @Autowired
    private BillConfigService billConfigService;

    // -----------------------------------------------------------------------
    // 距离（委托 RoutePriceCalculator，两种入参）
    // -----------------------------------------------------------------------

    /** 发布前预览用：WaypointDto 列表（与实体版重载泛型擦除冲突，故独立命名） */
    public BigDecimal estimateDistance(List<WaypointDto> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<TaskWaypoint> entities = waypoints.stream().map(wp -> {
            TaskWaypoint tw = new TaskWaypoint();
            tw.setOrderIndex(wp.getOrderIndex());
            tw.setLongitude(wp.getLongitude());
            tw.setLatitude(wp.getLatitude());
            tw.setAltitude(wp.getAltitude());
            return tw;
        }).toList();
        return RoutePriceCalculator.calculateTotalDistance(entities);
    }

    /** 创建任务用：TaskWaypoint 实体列表 */
    public BigDecimal calculateTotalDistance(List<TaskWaypoint> waypoints) {
        return RoutePriceCalculator.calculateTotalDistance(waypoints);
    }

    // -----------------------------------------------------------------------
    // 参考价
    // -----------------------------------------------------------------------

    public PriceDetailVO calculate(TaskType type, BigDecimal distanceMeters, Double weightKg, LocalDateTime plannedTime) {
        BigDecimal baseFee = big("baseFee", new BigDecimal("30"));
        BigDecimal baseDistanceKm = big("baseDistanceKm", new BigDecimal("3"));

        BigDecimal distanceKm = distanceMeters == null
                ? BigDecimal.ZERO
                : distanceMeters.divide(KM_TO_M, 4, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
        BigDecimal payableKm = distanceKm.subtract(baseDistanceKm).max(BigDecimal.ZERO);

        // 里程费：perKmSteps 多档优先，无则回退单档 perKmFee
        BigDecimal distanceFee = calcDistanceFee(payableKm);
        if (distanceFee == null) {
            BigDecimal perKmFee = big("perKmFee", new BigDecimal("3"));
            distanceFee = payableKm.multiply(perKmFee).setScale(2, RoundingMode.HALF_UP);
        }

        // 重量阶梯费：仅 WEIGHT_TYPES 中的类型参与；超档 → 按最高档计费 + 需人工报价
        BigDecimal weightFee = BigDecimal.ZERO;
        boolean needManualQuote = false;
        if (isWeightType(type) && weightKg != null && weightKg > 0) {
            List<WeightStep> steps = billConfigService.getJson("weightSteps",
                    new TypeReference<List<WeightStep>>() {}, DEFAULT_WEIGHT_STEPS);
            boolean matched = false;
            for (WeightStep step : steps) {
                if (BigDecimal.valueOf(weightKg).compareTo(step.getMaxKg()) <= 0) {
                    weightFee = step.getFee().setScale(2, RoundingMode.HALF_UP);
                    matched = true;
                    break;
                }
            }
            if (!matched && !steps.isEmpty()) {
                weightFee = steps.get(steps.size() - 1).getFee().setScale(2, RoundingMode.HALF_UP);
                needManualQuote = true;
            }
        }

        // 夜间附加费 = 前三项和 × nightRate
        boolean isNight = isNight(plannedTime);
        BigDecimal nightFee = BigDecimal.ZERO;
        if (isNight) {
            BigDecimal nightRate = big("nightRate", new BigDecimal("0.2"));
            nightFee = baseFee.add(distanceFee).add(weightFee)
                    .multiply(nightRate).setScale(2, RoundingMode.HALF_UP);
        }

        // items 顺序固定：起步价/里程费/重量费/夜间附加费，0 元项省略，total = 各项和
        List<PriceDetailVO.PriceItem> items = new ArrayList<>();
        if (baseFee.signum() > 0) items.add(new PriceDetailVO.PriceItem("起步价", baseFee));
        if (distanceFee.signum() > 0) items.add(new PriceDetailVO.PriceItem("里程费", distanceFee));
        if (weightFee.signum() > 0) items.add(new PriceDetailVO.PriceItem("重量费", weightFee));
        if (nightFee.signum() > 0) items.add(new PriceDetailVO.PriceItem("夜间附加费", nightFee));
        BigDecimal total = items.stream()
                .map(PriceDetailVO.PriceItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PriceDetailVO vo = new PriceDetailVO();
        vo.setTotal(total);
        vo.setItems(items);
        vo.setDistanceKm(distanceKm);
        vo.setWeightKg(weightKg);
        vo.setIsNight(isNight);
        vo.setNeedManualQuote(needManualQuote);
        return vo;
    }

    // -----------------------------------------------------------------------
    // 私有工具
    // -----------------------------------------------------------------------

    /**
     * 多档里程费：perKmSteps=[{maxKm,rate}] 升序，档位覆盖 (prevMax, maxKm]；
     * 超出最后一档的里程按最后一档 rate 计（最后档 maxKm 可省略表示无上限）。
     * 未配置多档 → 返回 null，由调用方回退单档 perKmFee。
     */
    private BigDecimal calcDistanceFee(BigDecimal payableKm) {
        List<PerKmStep> steps = billConfigService.getJson("perKmSteps",
                new TypeReference<List<PerKmStep>>() {}, List.of());
        if (steps.isEmpty()) {
            return null;
        }
        BigDecimal remaining = payableKm;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal prevMax = BigDecimal.ZERO;
        BigDecimal lastRate = null;
        for (PerKmStep step : steps) {
            if (step.getRate() == null) continue;
            lastRate = step.getRate();
            BigDecimal span = step.getMaxKm() == null
                    ? remaining
                    : step.getMaxKm().subtract(prevMax);
            if (span.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal used = span.min(remaining);
            fee = fee.add(used.multiply(step.getRate()));
            remaining = remaining.subtract(used);
            prevMax = step.getMaxKm() == null ? remaining : step.getMaxKm();
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0 && lastRate != null) {
            fee = fee.add(remaining.multiply(lastRate));
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    /** 该任务类型是否按重量计费（bill_config.WEIGHT_TYPES 配置） */
    private boolean isWeightType(TaskType type) {
        if (type == null) return false;
        List<String> types = billConfigService.getJson("WEIGHT_TYPES",
                new TypeReference<List<String>>() {}, List.of("TRANSPORT"));
        return types.contains(type.name());
    }

    /**
     * 夜间判断：按任务计划时间（不用 now() 兜底，防 UTC 容器时区漂移）。
     * 支持跨午夜区间（nightStart=22, nightEnd=6）。
     */
    private boolean isNight(LocalDateTime plannedTime) {
        if (plannedTime == null) return false;
        int start = billConfigService.getInt("nightStart", 22);
        int end = billConfigService.getInt("nightEnd", 6);
        LocalTime time = plannedTime.toLocalTime();
        LocalTime startTime = LocalTime.of(start, 0);
        LocalTime endTime = LocalTime.of(end, 0);
        if (start < end) {
            return !time.isBefore(startTime) && time.isBefore(endTime);
        }
        return !time.isBefore(startTime) || time.isBefore(endTime);
    }

    private BigDecimal big(String key, BigDecimal defaultValue) {
        return billConfigService.getBigDecimal(key, defaultValue);
    }

    // -----------------------------------------------------------------------
    // 配置结构（JSON 反序列化用）
    // -----------------------------------------------------------------------

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeightStep {
        /** 该档重量上限（kg），超出取下一档 */
        private BigDecimal maxKg;
        /** 该档费用（元） */
        private BigDecimal fee;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerKmStep {
        /** 该档里程上限（km），null = 无上限（最后一档） */
        private BigDecimal maxKm;
        /** 该档单价（元/km） */
        private BigDecimal rate;
    }
}

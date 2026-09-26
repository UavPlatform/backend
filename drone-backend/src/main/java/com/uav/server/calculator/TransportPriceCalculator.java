package com.uav.server.calculator;

import com.uav.server.config.OrderConfig;
import com.uav.server.config.TransportPricingConfig;
import com.uav.task.pojo.entity.TaskWaypoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 吊运平台计价引擎（REQ-BACKEND-001 计价规则，ADR-0003 决定 2「计价：服务端 SSOT」）。
 *
 * <p>公式：
 * <pre>
 * quotedAmount = (distanceCharge + weightCharge + categoryCharge) × aircraftModelCoefficient
 * </pre>
 * <ul>
 *   <li>{@code distanceCharge} = 航线距离（米）× {@code order.price-per-meter}（沿用 {@link RoutePriceCalculator} 距离算法与既有单价）</li>
 *   <li>{@code weightCharge} = 货物重量所在档的<b>阶梯费用</b>（{@code transport.pricing.weight-steps}）；
 *       超最高档按最高档计费。见 {@link #weightTierCharge}</li>
 *   <li>{@code categoryCharge} = 货物类别附加费（{@code transport.pricing.category-surcharge} 查表；未知类别取
 *       {@code transport.pricing.unknown-category-surcharge}，行为确定）</li>
 *   <li>{@code aircraftModelCoefficient} = 机型目录 {@code AircraftModel.coefficient}（由调用方传入）</li>
 * </ul>
 *
 * <p><b>为何重量用阶梯而非线性</b>：阶梯档位对应的是「这批货需要什么等级的机型」——
 * ≤10kg 普通机型、10~25kg 中型、&gt;25kg 重型；不同机型的出厂价差异很大，
 * 按档收费比「重量 × 单价」更贴近真实成本结构。
 *
 * <p><b>计价链</b>：{@link #quote} 由 {@link Quote} 逐步组装（载重门禁 → 里程 → 重量 → 类别 → 机型系数），
 * 每个要素一个独立步骤，新增或调整要素不影响其他步骤。
 *
 * <p><b>超重阈值</b>：货物重量严格大于机型最大载重时返回
 * {@link TransportPriceResult.Rejected}{@code (EXCEEDS_PAYLOAD)}，<b>不返回金额</b>；
 * 等于最大载重可正常计价（边界含）。
 *
 * <p><b>与 ADR-0003 首版的差异</b>：重量费由线性（重量 × {@code price-per-kg}）改为阶梯。
 * 阶梯是平台规则，不针对单笔订单调价；成交价仍须严格等于 {@code quotedAmount}。
 *
 * <p>纯计算组件：除构造期读取配置外不依赖 Spring 容器与 HTTP 层；单元测试可直接 {@code new}。
 * 所有金额 2 位小数 {@link RoundingMode#HALF_UP}，与既有 {@link RoutePriceCalculator#calculatePrice} 一致。
 */
@Component
public class TransportPriceCalculator {

    private static final int MONEY_SCALE = 2;

    private final BigDecimal pricePerMeter;
    /** 规范化后的重量阶梯：按 maxKg 升序排列 */
    private final List<TransportPricingConfig.WeightStep> weightSteps;
    /** 规范化后的类别附加费表：键为 trim + 小写后的类别码。 */
    private final Map<String, BigDecimal> categorySurcharge;
    private final BigDecimal unknownCategorySurcharge;

    /** Spring 装配入口：距离单价沿用 {@code order.price-per-meter}（{@link OrderConfig}），其余取 {@code transport.pricing.*}。 */
    @Autowired
    public TransportPriceCalculator(OrderConfig orderConfig, TransportPricingConfig pricingConfig) {
        this(orderConfig.getPricePerMeter(),
                pricingConfig.getWeightSteps(),
                pricingConfig.getCategorySurcharge(),
                pricingConfig.getUnknownCategorySurcharge());
    }

    /**
     * 纯构造（单元测试与非 Spring 调用方直接使用）。
     *
     * @throws IllegalArgumentException 配置非法（单价缺失或为负、阶梯为空或费用为负、附加费为负）——宁可启动失败也不产出错误价格
     */
    public TransportPriceCalculator(BigDecimal pricePerMeter,
                                    List<TransportPricingConfig.WeightStep> weightSteps,
                                    Map<String, BigDecimal> categorySurcharge,
                                    BigDecimal unknownCategorySurcharge) {
        if (pricePerMeter == null || pricePerMeter.signum() <= 0) {
            throw new IllegalArgumentException("距离单价非法（order.price-per-meter 须为正数）：" + pricePerMeter);
        }
        if (weightSteps == null || weightSteps.isEmpty()) {
            throw new IllegalArgumentException("重量阶梯不能为空（transport.pricing.weight-steps 至少一档）");
        }
        if (unknownCategorySurcharge == null || unknownCategorySurcharge.signum() < 0) {
            throw new IllegalArgumentException(
                    "未知类别附加费非法（transport.pricing.unknown-category-surcharge 须为非负数）：" + unknownCategorySurcharge);
        }
        this.pricePerMeter = pricePerMeter;
        this.unknownCategorySurcharge = unknownCategorySurcharge;

        for (TransportPricingConfig.WeightStep step : weightSteps) {
            if (step == null || step.getMaxKg() == null || step.getMaxKg().signum() <= 0) {
                throw new IllegalArgumentException("重量阶梯上限非法（max-kg 须为正数）："
                        + (step == null ? null : step.getMaxKg()));
            }
            if (step.getFee() == null || step.getFee().signum() < 0) {
                throw new IllegalArgumentException("重量阶梯费用非法（" + step.getMaxKg() + "kg 档须为非负数）：" + step.getFee());
            }
        }
        this.weightSteps = weightSteps.stream()
                .sorted(Comparator.comparing(TransportPricingConfig.WeightStep::getMaxKg))
                .toList();

        Map<String, BigDecimal> normalized = new HashMap<>();
        if (categorySurcharge != null) {
            for (Map.Entry<String, BigDecimal> entry : categorySurcharge.entrySet()) {
                String key = entry.getKey();
                BigDecimal value = entry.getValue();
                if (key == null || key.trim().isEmpty()) {
                    throw new IllegalArgumentException("货物类别附加费表存在空类别码");
                }
                if (value == null || value.signum() < 0) {
                    throw new IllegalArgumentException("货物类别附加费非法（" + key + "）：" + value);
                }
                normalized.put(key.trim().toLowerCase(Locale.ROOT), value);
            }
        }
        this.categorySurcharge = Map.copyOf(normalized);
    }

    /**
     * 按航点列表计价：距离复用 {@link RoutePriceCalculator#calculateTotalDistance}（haversine）。
     *
     * <p>参数语义与 {@link #quote(BigDecimal, BigDecimal, String, BigDecimal, BigDecimal)} 相同。
     */
    public TransportPriceResult quote(List<TaskWaypoint> waypoints,
                                      BigDecimal cargoWeightKg,
                                      String cargoCategory,
                                      BigDecimal aircraftModelCoefficient,
                                      BigDecimal maxPayloadKg) {
        return quote(RoutePriceCalculator.calculateTotalDistance(waypoints),
                cargoWeightKg, cargoCategory, aircraftModelCoefficient, maxPayloadKg);
    }

    /**
     * 按航线距离（米）计价。
     *
     * @param distanceMeters           航线距离（米），须非负
     * @param cargoWeightKg            货物重量（kg），须非负
     * @param cargoCategory            货物类别码（如 CONSTRUCTION/建材）；null/空白/未配置类别按未知类别附加费计费
     * @param aircraftModelCoefficient 机型系数（{@code AircraftModel.coefficient}），须为正数
     * @param maxPayloadKg             机型最大载重（kg），须为正数
     * @return {@link TransportPriceResult.Quote} 或带明确原因的 {@link TransportPriceResult.Rejected}
     */
    public TransportPriceResult quote(BigDecimal distanceMeters,
                                      BigDecimal cargoWeightKg,
                                      String cargoCategory,
                                      BigDecimal aircraftModelCoefficient,
                                      BigDecimal maxPayloadKg) {
        if (distanceMeters == null || distanceMeters.signum() < 0) {
            return rejected(TransportPriceRejection.INVALID_INPUT, "航线距离非法：" + distanceMeters);
        }
        if (cargoWeightKg == null || cargoWeightKg.signum() < 0) {
            return rejected(TransportPriceRejection.INVALID_INPUT, "货物重量非法：" + cargoWeightKg);
        }
        if (aircraftModelCoefficient == null || aircraftModelCoefficient.signum() <= 0) {
            return rejected(TransportPriceRejection.INVALID_INPUT, "机型系数非法：" + aircraftModelCoefficient);
        }
        if (maxPayloadKg == null || maxPayloadKg.signum() <= 0) {
            return rejected(TransportPriceRejection.INVALID_INPUT, "机型最大载重非法：" + maxPayloadKg);
        }

        // 计价链：每步只负责一个要素，互不影响
        try {
            return new Quote(distanceMeters, cargoWeightKg, cargoCategory, aircraftModelCoefficient)
                    .guardPayload(maxPayloadKg)
                    .mileage()
                    .weightTier()
                    .category()
                    .aircraft()
                    .build();
        } catch (RejectedQuote e) {
            return rejected(e.reason, e.getMessage());
        }
    }

    /** 计价链：逐步累加各要素，末步统一乘机型系数并收尾成 {@link TransportPriceResult.Quote}。 */
    private final class Quote {
        private final BigDecimal distanceMeters;
        private final BigDecimal cargoWeightKg;
        private final String cargoCategory;
        private final BigDecimal aircraftModelCoefficient;

        private BigDecimal distanceCharge = BigDecimal.ZERO;
        private BigDecimal weightCharge = BigDecimal.ZERO;
        private BigDecimal categoryCharge = BigDecimal.ZERO;

        private Quote(BigDecimal distanceMeters, BigDecimal cargoWeightKg,
                      String cargoCategory, BigDecimal aircraftModelCoefficient) {
            this.distanceMeters = distanceMeters;
            this.cargoWeightKg = cargoWeightKg;
            this.cargoCategory = cargoCategory;
            this.aircraftModelCoefficient = aircraftModelCoefficient;
        }

        /** 载重门禁（安全相关）：超载直接拒绝，不产生金额。 */
        private Quote guardPayload(BigDecimal maxPayloadKg) {
            if (cargoWeightKg.compareTo(maxPayloadKg) > 0) {
                throw new RejectedQuote(TransportPriceRejection.EXCEEDS_PAYLOAD,
                        "货物重量 " + cargoWeightKg + "kg 超过机型最大载重 " + maxPayloadKg + "kg，拒绝计价");
            }
            return this;
        }

        /** 里程费 = 航线距离 × 距离单价。 */
        private Quote mileage() {
            this.distanceCharge = money(pricePerMeter.multiply(distanceMeters));
            return this;
        }

        /** 重量费 = 货物重量所在档的阶梯费用；超最高档按最高档（不拒绝，由人工报价兜底）。 */
        private Quote weightTier() {
            this.weightCharge = money(weightTierCharge(cargoWeightKg));
            return this;
        }

        /** 类别附加费 = 类别查表；未知类别取 unknownCategorySurcharge。 */
        private Quote category() {
            this.categoryCharge = money(categorySurchargeFor(cargoCategory));
            return this;
        }

        /** 机型系数：对各项之和整体乘算。 */
        private Quote aircraft() {
            return this;
        }

        private TransportPriceResult.Quote build() {
            BigDecimal subtotal = distanceCharge.add(weightCharge).add(categoryCharge);
            if (aircraftModelCoefficient.compareTo(BigDecimal.ONE) != 0) {
                subtotal = subtotal.multiply(aircraftModelCoefficient);
            }
            return new TransportPriceResult.Quote(money(subtotal), distanceCharge, weightCharge,
                    categoryCharge, aircraftModelCoefficient);
        }
    }

    /** 计价链内部拒绝信号：由 {@link #quote} 转成 {@link TransportPriceResult.Rejected}。 */
    private static final class RejectedQuote extends RuntimeException {
        private final TransportPriceRejection reason;

        private RejectedQuote(TransportPriceRejection reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    /** 取货物重量所在档的费用；超最高档返回最高档费用。 */
    private BigDecimal weightTierCharge(BigDecimal cargoWeightKg) {
        for (TransportPricingConfig.WeightStep step : weightSteps) {
            if (cargoWeightKg.compareTo(step.getMaxKg()) <= 0) {
                return step.getFee();
            }
        }
        return weightSteps.get(weightSteps.size() - 1).getFee();
    }

    private BigDecimal categorySurchargeFor(String cargoCategory) {
        if (cargoCategory == null) {
            return unknownCategorySurcharge;
        }
        return categorySurcharge.getOrDefault(cargoCategory.trim().toLowerCase(Locale.ROOT),
                unknownCategorySurcharge);
    }

    private static TransportPriceResult rejected(TransportPriceRejection reason, String message) {
        return new TransportPriceResult.Rejected(reason, message);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}

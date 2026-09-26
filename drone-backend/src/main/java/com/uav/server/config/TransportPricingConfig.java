package com.uav.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 吊运计价可配置项（REQ-BACKEND-001「基础单价/类别附加费可配置」）。
 *
 * <p>距离单价不在本类：沿用 {@link OrderConfig#getPricePerMeter()}（{@code order.price-per-meter}），
 * 由 {@link com.uav.server.calculator.TransportPriceCalculator} 注入，保持单一来源。
 *
 * <p><b>重量费为阶梯制</b>（{@link WeightStep}）：按货物重量落在哪一档取该档固定费用，
 * 而不是「重量 × 单价」的线性计费。阶梯对应所需机型等级——≤10kg 普通机型、10~25kg 中型、
 * &gt;25kg 重型，不同机型的出厂价差异很大，按档计费比线性更贴近真实成本结构。
 * 超出最高档按最高档计费（由计算器处理），不拒绝报价。
 *
 * <pre>
 * transport:
 *   pricing:
 *     weight-steps:
 *       - max-kg: 10
 *         fee: 0
 *       - max-kg: 25
 *         fee: 30
 *       - max-kg: 50
 *         fee: 80
 *     unknown-category-surcharge: 0.00
 *     category-surcharge:
 *       CONSTRUCTION: 50.00   # 建材
 *       EQUIPMENT: 80.00      # 设备
 *       AGRICULTURAL: 30.00   # 农产品
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "transport.pricing")
public class TransportPricingConfig {

    /** 重量阶梯（元），按 maxKg 升序；须非空且费用非负。null 由计算器构造期校验拒绝启动。 */
    private List<WeightStep> weightSteps = new ArrayList<>();

    /** 货物类别附加费表（元），键为类别码（如 CONSTRUCTION/EQUIPMENT/AGRICULTURAL，查找大小写不敏感）。 */
    private Map<String, BigDecimal> categorySurcharge = new HashMap<>();

    /** 未知类别附加费（元）：类别不在表中时按此值计费，保证行为确定、可审计。 */
    private BigDecimal unknownCategorySurcharge = BigDecimal.ZERO;

    /** 重量阶梯的一档：货物重量 ≤ {@code maxKg} 时取 {@code fee}。 */
    @Getter
    @Setter
    public static class WeightStep {
        /** 该档重量上限（kg），含边界 */
        private BigDecimal maxKg;
        /** 该档费用（元） */
        private BigDecimal fee;
    }
}

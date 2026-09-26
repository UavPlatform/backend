package com.uav.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 吊运计价可配置项（REQ-BACKEND-001「基础单价/类别附加费可配置」）。
 *
 * <p>距离单价不在本类：沿用 {@link OrderConfig#getPricePerMeter()}（{@code order.price-per-meter}），
 * 由 {@link com.uav.server.calculator.TransportPriceCalculator} 注入，保持单一来源。
 *
 * <p>ADR-0003「不允许改价」：本配置只承载平台规则单价，不提供任何针对单笔订单的
 * 调价/折扣字段；调价须改配置并留痕，不得按订单 override。
 *
 * <pre>
 * transport:
 *   pricing:
 *     price-per-kg: 2.00
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

    /** 重量单价（元/kg）：{@code weightCharge = 货物重量 × 本单价}。null 由计算器构造期校验拒绝启动。 */
    private BigDecimal pricePerKg;

    /** 货物类别附加费表（元），键为类别码（如 CONSTRUCTION/EQUIPMENT/AGRICULTURAL，查找大小写不敏感）。 */
    private Map<String, BigDecimal> categorySurcharge = new HashMap<>();

    /** 未知类别附加费（元）：类别不在表中时按此值计费，保证行为确定、可审计。 */
    private BigDecimal unknownCategorySurcharge = BigDecimal.ZERO;
}

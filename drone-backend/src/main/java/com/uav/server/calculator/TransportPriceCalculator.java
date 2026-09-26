package com.uav.server.calculator;

import com.uav.server.config.OrderConfig;
import com.uav.server.config.TransportPricingConfig;
import com.uav.task.pojo.entity.TaskWaypoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 吊运平台计价引擎（REQ-BACKEND-001 计价规则，ADR-0003 决定 2「计价：服务端 SSOT」）。
 *
 * <p>公式（ADR-0003 首版）：
 * <pre>
 * quotedAmount = (distanceCharge + weightCharge + categoryCharge) × aircraftModelCoefficient
 * </pre>
 * <ul>
 *   <li>{@code distanceCharge} = 航线距离（米）× {@code order.price-per-meter}（沿用 {@link RoutePriceCalculator} 距离算法与既有单价）</li>
 *   <li>{@code weightCharge} = 货物重量（kg）× 重量单价（{@code transport.pricing.price-per-kg}）</li>
 *   <li>{@code categoryCharge} = 货物类别附加费（{@code transport.pricing.category-surcharge} 查表；未知类别取
 *       {@code transport.pricing.unknown-category-surcharge}，行为确定）</li>
 *   <li>{@code aircraftModelCoefficient} = 机型目录 {@code AircraftModel.coefficient}（由调用方传入）</li>
 * </ul>
 *
 * <p><b>ADR-0003「不允许改价」</b>：本计算器只接受规则因子（距离、重量、类别、机型系数、最大载重），
 * <b>不暴露任何调价/折扣/override 参数</b>——输出即系统报价 {@code quotedAmount}，应征与下单须锁定该值，
 * 成交价必须严格等于它。放开议价须新建 ADR，不得在本类下悄悄加参数。
 *
 * <p>超重阈值：货物重量严格大于机型最大载重时返回
 * {@link TransportPriceResult.Rejected}{@code (EXCEEDS_PAYLOAD)}，<b>不返回金额</b>；
 * 等于最大载重可正常计价（边界含）。
 *
 * <p>纯计算组件：除构造期读取配置外不依赖 Spring 容器与 HTTP 层；单元测试可直接 {@code new}。
 * 所有金额 2 位小数 {@link RoundingMode#HALF_UP}，与既有 {@link RoutePriceCalculator#calculatePrice} 一致。
 */
@Component
public class TransportPriceCalculator {

    private static final int MONEY_SCALE = 2;

    private final BigDecimal pricePerMeter;
    private final BigDecimal pricePerKg;
    /** 规范化后的类别附加费表：键为 trim + 小写后的类别码。 */
    private final Map<String, BigDecimal> categorySurcharge;
    private final BigDecimal unknownCategorySurcharge;

    /** Spring 装配入口：距离单价沿用 {@code order.price-per-meter}（{@link OrderConfig}），其余取 {@code transport.pricing.*}。 */
    @Autowired
    public TransportPriceCalculator(OrderConfig orderConfig, TransportPricingConfig pricingConfig) {
        this(orderConfig.getPricePerMeter(),
                pricingConfig.getPricePerKg(),
                pricingConfig.getCategorySurcharge(),
                pricingConfig.getUnknownCategorySurcharge());
    }

    /**
     * 纯构造（单元测试与非 Spring 调用方直接使用）。
     *
     * @throws IllegalArgumentException 配置非法（单价缺失或为负、附加费为负）——宁可启动失败也不产出错误价格
     */
    public TransportPriceCalculator(BigDecimal pricePerMeter,
                                    BigDecimal pricePerKg,
                                    Map<String, BigDecimal> categorySurcharge,
                                    BigDecimal unknownCategorySurcharge) {
        if (pricePerMeter == null || pricePerMeter.signum() <= 0) {
            throw new IllegalArgumentException("距离单价非法（order.price-per-meter 须为正数）：" + pricePerMeter);
        }
        if (pricePerKg == null || pricePerKg.signum() < 0) {
            throw new IllegalArgumentException("重量单价非法（transport.pricing.price-per-kg 须为非负数）：" + pricePerKg);
        }
        if (unknownCategorySurcharge == null || unknownCategorySurcharge.signum() < 0) {
            throw new IllegalArgumentException(
                    "未知类别附加费非法（transport.pricing.unknown-category-surcharge 须为非负数）：" + unknownCategorySurcharge);
        }
        this.pricePerMeter = pricePerMeter;
        this.pricePerKg = pricePerKg;
        this.unknownCategorySurcharge = unknownCategorySurcharge;

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
     * @param distanceMeters          起吊点 → 卸货点的航线距离（米），须非负
     * @param cargoWeightKg           货物重量（kg），须非负
     * @param cargoCategory           货物类别码（如 CONSTRUCTION/建材）；null/空白/未配置类别按未知类别附加费计费
     * @param aircraftModelCoefficient 机型系数（{@code AircraftModel.coefficient}），须为正数
     * @param maxPayloadKg            机型最大载重（kg），须为正数
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
        if (cargoWeightKg.compareTo(maxPayloadKg) > 0) {
            return rejected(TransportPriceRejection.EXCEEDS_PAYLOAD,
                    "货物重量 " + cargoWeightKg + "kg 超过机型最大载重 " + maxPayloadKg + "kg，拒绝计价");
        }

        BigDecimal distanceCharge = money(pricePerMeter.multiply(distanceMeters));
        BigDecimal weightCharge = money(pricePerKg.multiply(cargoWeightKg));
        BigDecimal categoryCharge = money(categorySurchargeFor(cargoCategory));
        BigDecimal quotedAmount = money(distanceCharge.add(weightCharge).add(categoryCharge)
                .multiply(aircraftModelCoefficient));

        return new TransportPriceResult.Quote(quotedAmount, distanceCharge, weightCharge,
                categoryCharge, aircraftModelCoefficient);
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

package com.uav.server.calculator;

import com.uav.task.pojo.entity.TaskWaypoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 吊运计价引擎单元测试（REQ-BACKEND-001 计价规则 / ADR-0003）。
 *
 * <p>R1：纯 JUnit + AssertJ，不起 Spring 上下文；计算器直接 {@code new} 构造。
 *
 * <p>覆盖核心路径：公式与明细、同输入同输出、机型系数影响报价、零距离、
 * 超重边界（等于可计价 / 超过拒绝）、未知类别、非法入参与非法配置。
 *
 * <p>固定配置：距离单价 0.05 元/米（= application.yml 的 order.price-per-meter）、
 * 重量单价 2.00 元/kg、CONSTRUCTION 50.00 元、EQUIPMENT 80.00 元、未知类别 0.00 元。
 */
class TransportPriceCalculatorTest {

    private static final BigDecimal PRICE_PER_METER = new BigDecimal("0.05");
    private static final BigDecimal PRICE_PER_KG = new BigDecimal("2.00");
    private static final BigDecimal UNKNOWN_SURCHARGE = new BigDecimal("0.00");
    private static final Map<String, BigDecimal> CATEGORY_SURCHARGE = Map.of(
            "CONSTRUCTION", new BigDecimal("50.00"),
            "EQUIPMENT", new BigDecimal("80.00"));

    private static TransportPriceCalculator calculator() {
        return new TransportPriceCalculator(PRICE_PER_METER, PRICE_PER_KG, CATEGORY_SURCHARGE, UNKNOWN_SURCHARGE);
    }

    private static TransportPriceResult.Quote quoteOf(TransportPriceResult result) {
        assertThat(result).isInstanceOf(TransportPriceResult.Quote.class);
        return (TransportPriceResult.Quote) result;
    }

    private static TransportPriceResult.Rejected rejectedOf(TransportPriceResult result) {
        assertThat(result).isInstanceOf(TransportPriceResult.Rejected.class);
        return (TransportPriceResult.Rejected) result;
    }

    @Test
    @DisplayName("ADR-0003 公式：quotedAmount =（距离费+重量费+类别费）×机型系数，明细与总量 2 位小数 HALF_UP")
    void quoteFollowsAdr0003FormulaWithBreakdown() {
        TransportPriceResult.Quote quote = quoteOf(calculator().quote(
                new BigDecimal("1234.56"), new BigDecimal("15"), "CONSTRUCTION",
                new BigDecimal("1.000"), new BigDecimal("30")));

        assertThat(quote.distanceCharge()).isEqualTo(new BigDecimal("61.73"));   // 1234.56 × 0.05 = 61.728 → 61.73
        assertThat(quote.weightCharge()).isEqualTo(new BigDecimal("30.00"));     // 15 × 2.00
        assertThat(quote.categoryCharge()).isEqualTo(new BigDecimal("50.00"));
        assertThat(quote.aircraftModelCoefficient()).isEqualByComparingTo("1.000");
        assertThat(quote.quotedAmount())
                .isEqualTo(new BigDecimal("141.73"));                            // (61.73 + 30.00 + 50.00) × 1.000
    }

    @Test
    @DisplayName("同输入同输出：重复计价结果逐字段相等（可复现报价）")
    void sameInputProducesSameQuote() {
        TransportPriceCalculator calc = calculator();

        TransportPriceResult.Quote first = quoteOf(calc.quote(
                new BigDecimal("1234.56"), new BigDecimal("15"), "CONSTRUCTION",
                new BigDecimal("1.200"), new BigDecimal("30")));
        TransportPriceResult.Quote second = quoteOf(calc.quote(
                new BigDecimal("1234.56"), new BigDecimal("15"), "CONSTRUCTION",
                new BigDecimal("1.200"), new BigDecimal("30")));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("机型影响报价：同任务同货物，系数 1.000 与 1.200 产生不同且可复现的 quotedAmount")
    void differentAircraftCoefficientProducesDifferentQuote() {
        TransportPriceCalculator calc = calculator();

        TransportPriceResult.Quote fc30 = quoteOf(calc.quote(
                new BigDecimal("1234.56"), new BigDecimal("15"), "CONSTRUCTION",
                new BigDecimal("1.000"), new BigDecimal("30")));
        TransportPriceResult.Quote m350 = quoteOf(calc.quote(
                new BigDecimal("1234.56"), new BigDecimal("15"), "CONSTRUCTION",
                new BigDecimal("1.200"), new BigDecimal("30")));

        assertThat(fc30.quotedAmount()).isEqualTo(new BigDecimal("141.73"));
        assertThat(m350.quotedAmount()).isEqualTo(new BigDecimal("170.08")); // 141.73 × 1.2 = 170.076 → 170.08
        assertThat(m350.quotedAmount()).isNotEqualByComparingTo(fc30.quotedAmount());
    }

    @Test
    @DisplayName("零距离：距离费为 0，报价 =（重量费+类别费）×系数，仍正常出价")
    void zeroDistanceChargesOnlyWeightAndCategory() {
        TransportPriceResult.Quote quote = quoteOf(calculator().quote(
                BigDecimal.ZERO, new BigDecimal("10"), null,
                new BigDecimal("1.000"), new BigDecimal("30")));

        assertThat(quote.distanceCharge()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.weightCharge()).isEqualTo(new BigDecimal("20.00"));
        assertThat(quote.categoryCharge()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.quotedAmount()).isEqualTo(new BigDecimal("20.00"));
    }

    @Test
    @DisplayName("超重边界：重量等于最大载重可计价（边界含）")
    void weightAtMaxPayloadStillQuoted() {
        TransportPriceResult.Quote quote = quoteOf(calculator().quote(
                BigDecimal.ZERO, new BigDecimal("30"), null,
                new BigDecimal("1.000"), new BigDecimal("30")));

        assertThat(quote.quotedAmount()).isEqualTo(new BigDecimal("60.00"));
    }

    @Test
    @DisplayName("超重拒绝：重量超过最大载重返回 EXCEEDS_PAYLOAD，不返回任何金额")
    void exceedingPayloadRejectedWithoutAmount() {
        TransportPriceResult.Rejected rejected = rejectedOf(calculator().quote(
                BigDecimal.ZERO, new BigDecimal("30.001"), null,
                new BigDecimal("1.000"), new BigDecimal("30")));

        assertThat(rejected.reason()).isEqualTo(TransportPriceRejection.EXCEEDS_PAYLOAD);
        assertThat(rejected.message()).contains("30.001").contains("30");
    }

    @Test
    @DisplayName("类别附加费：已配置类别按表计费，查找大小写与空白不敏感")
    void knownCategoryUsesConfiguredSurcharge() {
        TransportPriceResult.Quote quote = quoteOf(calculator().quote(
                BigDecimal.ZERO, new BigDecimal("1"), "  Construction  ",
                new BigDecimal("1.000"), new BigDecimal("30")));

        assertThat(quote.categoryCharge()).isEqualTo(new BigDecimal("50.00"));
        assertThat(quote.quotedAmount()).isEqualTo(new BigDecimal("52.00"));
    }

    @Test
    @DisplayName("未知类别行为明确：不在附加费表中按 unknown-category-surcharge 计费，不拒绝")
    void unknownCategoryUsesDefaultSurcharge() {
        TransportPriceCalculator calc = calculator();

        for (String unknown : new String[]{"LIQUID", "", "   ", null}) {
            TransportPriceResult.Quote quote = quoteOf(calc.quote(
                    BigDecimal.ZERO, new BigDecimal("1"), unknown,
                    new BigDecimal("1.000"), new BigDecimal("30")));
            assertThat(quote.categoryCharge()).isEqualTo(UNKNOWN_SURCHARGE);
        }
    }

    @Test
    @DisplayName("非法入参拒绝计价：负距离/负重量/非正系数/非正载重 → INVALID_INPUT，不返回金额")
    void invalidInputsRejected() {
        TransportPriceCalculator calc = calculator();

        assertThat(rejectedOf(calc.quote(new BigDecimal("-1"), new BigDecimal("1"), null,
                new BigDecimal("1.000"), new BigDecimal("30"))).reason())
                .isEqualTo(TransportPriceRejection.INVALID_INPUT);
        assertThat(rejectedOf(calc.quote(BigDecimal.ZERO, new BigDecimal("-1"), null,
                new BigDecimal("1.000"), new BigDecimal("30"))).reason())
                .isEqualTo(TransportPriceRejection.INVALID_INPUT);
        assertThat(rejectedOf(calc.quote(BigDecimal.ZERO, new BigDecimal("1"), null,
                BigDecimal.ZERO, new BigDecimal("30"))).reason())
                .isEqualTo(TransportPriceRejection.INVALID_INPUT);
        assertThat(rejectedOf(calc.quote(BigDecimal.ZERO, new BigDecimal("1"), null,
                new BigDecimal("1.000"), BigDecimal.ZERO)).reason())
                .isEqualTo(TransportPriceRejection.INVALID_INPUT);
    }

    @Test
    @DisplayName("非法计价配置在构造期拒绝（宁可启动失败也不产出错误价格）")
    void invalidPricingConfigRejectedAtConstruction() {
        assertThatThrownBy(() -> new TransportPriceCalculator(
                null, PRICE_PER_KG, CATEGORY_SURCHARGE, UNKNOWN_SURCHARGE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransportPriceCalculator(
                PRICE_PER_METER, new BigDecimal("-0.01"), CATEGORY_SURCHARGE, UNKNOWN_SURCHARGE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransportPriceCalculator(
                PRICE_PER_METER, PRICE_PER_KG, Map.of("CONSTRUCTION", new BigDecimal("-1")), UNKNOWN_SURCHARGE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("航点重载：距离经 RoutePriceCalculator haversine 计算，与直接传距离等价；航点不足按零距离计价")
    void waypointOverloadReusesRouteDistance() {
        TransportPriceCalculator calc = calculator();
        List<TaskWaypoint> route = List.of(waypoint(39.9042, 116.4074), waypoint(31.2304, 121.4737));

        BigDecimal distance = RoutePriceCalculator.calculateTotalDistance(route);
        assertThat(distance).isPositive();

        TransportPriceResult.Quote fromWaypoints = quoteOf(calc.quote(
                route, new BigDecimal("15"), "EQUIPMENT", new BigDecimal("1.200"), new BigDecimal("30")));
        TransportPriceResult.Quote fromDistance = quoteOf(calc.quote(
                distance, new BigDecimal("15"), "EQUIPMENT", new BigDecimal("1.200"), new BigDecimal("30")));
        assertThat(fromWaypoints).isEqualTo(fromDistance);

        TransportPriceResult.Quote tooFewWaypoints = quoteOf(calc.quote(
                List.of(waypoint(39.9042, 116.4074)), new BigDecimal("15"), "EQUIPMENT",
                new BigDecimal("1.200"), new BigDecimal("30")));
        assertThat(tooFewWaypoints.distanceCharge()).isEqualTo(new BigDecimal("0.00"));
    }

    private static TaskWaypoint waypoint(double latitude, double longitude) {
        TaskWaypoint waypoint = new TaskWaypoint();
        waypoint.setLatitude(latitude);
        waypoint.setLongitude(longitude);
        return waypoint;
    }
}

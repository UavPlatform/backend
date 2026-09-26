package com.uav.server.calculator;

import java.math.BigDecimal;

/**
 * 吊运计价结果：要么 {@link Quote}（成功，含系统报价与明细），要么 {@link Rejected}（明确拒绝原因）。
 *
 * <p>密封类型强制调用方穷举处理拒绝分支，从类型上保证「拒绝时不返回错误金额」
 * （REQ-BACKEND-001：超重等场景返回拒绝原因，而非一个错误的数字）。
 *
 * <p>ADR-0003「不允许改价」：成功结果中的 {@link Quote#quotedAmount()} 即系统报价，
 * 后续成交价必须严格等于它；本类型不携带任何可覆盖/可议价字段。
 */
public sealed interface TransportPriceResult
        permits TransportPriceResult.Quote, TransportPriceResult.Rejected {

    /**
     * 计价成功：{@code quotedAmount = (distanceCharge + weightCharge + categoryCharge) × aircraftModelCoefficient}
     * （ADR-0003 首版公式），全部金额 2 位小数 HALF_UP。
     *
     * @param quotedAmount            系统报价（成交价必须严格等于该值，不允许改价）
     * @param distanceCharge          距离费 = 航线距离 × order.price-per-meter
     * @param weightCharge            重量费 = 货物重量 × 重量单价
     * @param categoryCharge          类别附加费（查配置表；未知类别取默认附加费）
     * @param aircraftModelCoefficient 机型系数（AircraftModel.coefficient）
     */
    record Quote(
            BigDecimal quotedAmount,
            BigDecimal distanceCharge,
            BigDecimal weightCharge,
            BigDecimal categoryCharge,
            BigDecimal aircraftModelCoefficient
    ) implements TransportPriceResult {
    }

    /**
     * 计价被拒绝：不携带任何金额字段，调用方须把 {@link #reason()} 透出给上游。
     *
     * @param reason  拒绝原因（如 {@link TransportPriceRejection#EXCEEDS_PAYLOAD}）
     * @param message 人类可读的拒绝说明
     */
    record Rejected(TransportPriceRejection reason, String message) implements TransportPriceResult {
    }
}

package com.uav.server.calculator;

/**
 * 吊运计价拒绝原因（REQ-BACKEND-001 计价规则 / ADR-0003）。
 *
 * <p>计价被拒绝时返回本枚举，<b>不返回任何金额</b>——调用方不得把拒绝结果当成 0 元或默认报价使用。
 */
public enum TransportPriceRejection {

    /** 货物重量超过机型最大载重，拒绝计价（ADR-0003：超重阈值可拒绝应征）。 */
    EXCEEDS_PAYLOAD,

    /** 入参非法（距离/重量为负、机型系数非正、最大载重非正等），拒绝计价。 */
    INVALID_INPUT
}

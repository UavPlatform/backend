package com.uav.dispatch.pojo.vo;

import java.math.BigDecimal;

/** Confirmed fixed amounts only; not a quote or a settlement bill. */
public record DispatchConfigVO(String currency, BigDecimal depositAmount,
                               BigDecimal pilotServiceFee, boolean orderingEnabled) {
}

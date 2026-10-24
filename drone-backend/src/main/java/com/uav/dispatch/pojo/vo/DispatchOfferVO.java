package com.uav.dispatch.pojo.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/** Public pilot hall card deliberately excludes contacts, notes, and payment/refund references. */
public record DispatchOfferVO(String orderId, long offerRevision, Map<String, String> fields,
                              BigDecimal pilotServiceFee, LocalDateTime createdAt) { }

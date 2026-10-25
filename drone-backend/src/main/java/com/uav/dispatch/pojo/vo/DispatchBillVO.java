package com.uav.dispatch.pojo.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DispatchBillVO(DispatchQuoteVO quote, BigDecimal finalAmount, BigDecimal balanceAmount,
        String settlementReference, LocalDateTime settledAt, Integer reviewScore, String reviewComment,
        String settlementBasis, Long actualFlightSeconds) { }

package com.uav.dispatch.pojo.vo;
import java.math.BigDecimal;

/** Owner-approved temporary distance rule; no time/model/weight/category surcharges. */
public record DispatchQuoteVO(String rule, String distanceBasis, BigDecimal distanceMeters,
        BigDecimal pricePerMeter, BigDecimal distanceCharge, BigDecimal pilotServiceFee,
        BigDecimal totalAmount, BigDecimal depositAmount) { }

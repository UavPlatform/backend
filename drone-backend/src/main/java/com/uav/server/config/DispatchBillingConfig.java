package com.uav.server.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * ADR-0007 #4: final amount from the actually executed drone and actual flight duration (simplified v1).
 * equipment fee = max(ratePerMinute × ceil(flight minutes) × aircraft model coefficient, minimumEquipmentCharge);
 * final amount = equipment fee + pilot service fee. Platform-configurable; defaults are placeholders.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "dispatch.billing")
public class DispatchBillingConfig {
    @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2)
    private BigDecimal ratePerMinute = new BigDecimal("5.00");
    @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2)
    private BigDecimal minimumEquipmentCharge = new BigDecimal("20.00");

    public BigDecimal getRatePerMinute() { return ratePerMinute; }
    public void setRatePerMinute(BigDecimal ratePerMinute) { this.ratePerMinute = ratePerMinute; }
    public BigDecimal getMinimumEquipmentCharge() { return minimumEquipmentCharge; }
    public void setMinimumEquipmentCharge(BigDecimal minimumEquipmentCharge) { this.minimumEquipmentCharge = minimumEquipmentCharge; }
}

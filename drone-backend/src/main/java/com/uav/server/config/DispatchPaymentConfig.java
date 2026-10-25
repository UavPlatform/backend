package com.uav.server.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Platform fixed amounts in CNY (REQ-SHIP-PUB-003, REQ-SHIP-ACPT-003 / ADR-0007).
 * The owner confirmed a 100 CNY deposit and a separate 100 CNY pilot fee per order.
 * This configuration alone does not activate dispatch orders or payments.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "dispatch.payment")
public class DispatchPaymentConfig {

    /** Fixed deposit, independent of the estimate; precision matches existing money columns. */
    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal depositAmount = new BigDecimal("100.00");

    /** Fixed pilot service fee per order; independent of the deposit and flight duration. */
    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal pilotServiceFee = new BigDecimal("100.00");

    public DispatchPaymentConfig(Environment environment) {
        // A blank numeric property can otherwise be treated as absent by the binder.
        for (String property : new String[]{"dispatch.payment.deposit-amount",
                "dispatch.payment.pilot-service-fee"}) {
            String configured = Binder.get(environment).bind(property, String.class).orElse(null);
            if (configured != null && configured.isBlank()) {
                throw new IllegalArgumentException(property + " must not be blank");
            }
        }
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public void setDepositAmount(BigDecimal depositAmount) {
        this.depositAmount = depositAmount;
    }

    public BigDecimal getPilotServiceFee() {
        return pilotServiceFee;
    }

    public void setPilotServiceFee(BigDecimal pilotServiceFee) {
        this.pilotServiceFee = pilotServiceFee;
    }
}

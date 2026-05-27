package com.drone.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class OrderConfig {

    @Value("${order.price-per-meter}")
    private BigDecimal pricePerMeter;

    public BigDecimal getPricePerMeter() {
        return pricePerMeter;
    }
}

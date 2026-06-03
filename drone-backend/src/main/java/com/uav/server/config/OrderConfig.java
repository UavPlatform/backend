package com.uav.server.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Getter
public class OrderConfig {

    @Value("${order.price-per-meter}")
    private BigDecimal pricePerMeter;

}

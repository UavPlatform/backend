package com.uav.equipment.pojo.vo;
import java.math.BigDecimal;
public record ProviderDroneVO(String droneId, Long aircraftModelId,
        String modelName, String serialNumber, String airportName, BigDecimal latitude, BigDecimal longitude,
        boolean enabled, boolean borrowed, boolean borrowedByMe, String currentBorrowId) { }

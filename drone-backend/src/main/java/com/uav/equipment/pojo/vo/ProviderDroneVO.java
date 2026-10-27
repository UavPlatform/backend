package com.uav.equipment.pojo.vo;
/** Pilot catalog/borrow row: airport name from joined airport; no drone coordinates (ADR-0012). */
public record ProviderDroneVO(String droneId, Long aircraftModelId,
        String modelName, String serialNumber, Long airportId, String airportName,
        boolean enabled, boolean borrowed, boolean borrowedByMe, String currentBorrowId) { }

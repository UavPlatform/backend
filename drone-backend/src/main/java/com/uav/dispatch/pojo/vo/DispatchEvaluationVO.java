package com.uav.dispatch.pojo.vo;

import java.math.BigDecimal;
import java.util.List;

public record DispatchEvaluationVO(String airspace, String source, BigDecimal distanceMeters,
                                   BigDecimal flightMinutes, BigDecimal altitudeDifferenceMeters,
                                   String terrain, List<String> warnings) {
    public boolean complete() {
        return source != null && !source.isBlank() && distanceMeters != null && distanceMeters.signum() > 0
                && flightMinutes != null && flightMinutes.signum() > 0
                && altitudeDifferenceMeters != null && terrain != null && !terrain.isBlank()
                && airspace != null && List.of("ALLOWED", "CAUTION", "PROHIBITED").contains(airspace) && warnings != null;
    }
}

package com.uav.flight.pojo.vo;

import java.util.List;
import java.util.Map;

/** Sparse sensor values each retain their own capture time. No synthetic combined sample. */
public record FlightProjectionVO(long projectionRevision, List<Aircraft> aircraft, FlightEventVO lastPosition,
        boolean stale, List<Period> flightPeriods) {
    public record Aircraft(String serialNumber, String serialSource, String modelCode,
            Map<String, FlightEventVO> latest, boolean stale) { }
    /** Null boundaries mean unknown, never inferred from position or motor events. */
    public record Period(String serialNumber, String serialSource, Long takeoffAt, Long landedAt) { }
}

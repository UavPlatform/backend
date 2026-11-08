package com.uav.flight.service;

import com.uav.flight.pojo.vo.FlightEventVO;
import com.uav.flight.pojo.vo.FlightProjectionVO;
import java.util.*;

/** Only actual state transitions provide flight boundaries. */
final class FlightPeriods {
    private FlightPeriods() { }
    static List<FlightProjectionVO.Period> from(List<FlightEventVO> events) {
        var state = new HashMap<String, Boolean>();
        var starts = new LinkedHashMap<String, FlightEventVO>();
        var output = new ArrayList<FlightProjectionVO.Period>();
        for (var event : events) {
            if (!"ACTUAL_FLYING_STATE".equals(event.type()) || !(event.payload().get("flying") instanceof Boolean flying)) continue;
            String key = event.serialNumber() + "\u0000" + event.serialSource();
            Boolean previous = state.put(key, flying);
            if (Objects.equals(previous, flying)) continue;
            if (flying) starts.put(key, event);
            else {
                var start = starts.remove(key);
                output.add(new FlightProjectionVO.Period(event.serialNumber(), event.serialSource(),
                        start == null ? null : start.capturedAt(), event.capturedAt()));
            }
        }
        starts.values().forEach(e -> output.add(new FlightProjectionVO.Period(e.serialNumber(), e.serialSource(), e.capturedAt(), null)));
        return List.copyOf(output);
    }
}

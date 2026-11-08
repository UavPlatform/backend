package com.uav.flight.pojo.vo;

import java.util.List;

public record FlightEventPageVO(List<FlightEventVO> items, long projectionRevision, long nextAfter,
        Long nextCapturedAt, String nextEventId, boolean hasNext) { }

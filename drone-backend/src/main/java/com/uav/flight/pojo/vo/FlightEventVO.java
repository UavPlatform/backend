package com.uav.flight.pojo.vo;

import java.util.Map;

/** A read-only projection of an accepted event; units are the event contract units. */
public record FlightEventVO(long changeSequence, String eventId, String serialNumber, String serialSource,
        String modelCode, String type, long capturedAt, long receivedAt, Map<String, Object> payload) { }

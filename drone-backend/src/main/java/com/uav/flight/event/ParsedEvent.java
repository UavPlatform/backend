package com.uav.flight.event;

/**
 * {@code semanticReject} is null when the payload matches the draft for {@code type}.
 * A non-null token rejects only this event; the packet hash still covers it.
 */
public record ParsedEvent(
        int ordinal,
        String eventId,
        long capturedAt,
        String type,
        byte[] payloadCanonical,
        String semanticReject) { }

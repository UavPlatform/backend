package com.uav.flight.event;

import java.util.List;

/**
 * Fixed per-event result. Interval uses already accepted events only; a later gap fill must not rewrite it.
 * Cross-packet spacing therefore keeps the first stored event. That choice is arrival-order dependent and unfrozen.
 */
public final class EventDecider {
    public record Prior(String type, long capturedAt) { }
    public record Existing(String packetId, EventAttribution attribution) { }
    public record Decision(String outcome, String reason, String sourcePacketId) { }

    private EventDecider() { }

    public static Decision decide(ParsedEvent event, EventAttribution attribution, long now,
            List<Prior> acceptedInPacket, Existing existing, boolean storedIntervalHit,
            AssignmentCaptureWindow.Span assignmentWindow) {
        if (event.semanticReject() != null) return rejected(event.semanticReject());
        if (event.capturedAt() < now - EventLimits.CAPTURED_PAST_MS || event.capturedAt() > now + EventLimits.CAPTURED_FUTURE_MS) {
            return rejected("EVENT_TIME_WINDOW");
        }
        if (assignmentWindow == null || !assignmentWindow.contains(event.capturedAt())) return rejected("ASSIGNMENT_WINDOW");
        if (existing != null) {
            if (existing.attribution().same(attribution)) return new Decision("DUPLICATE", "DUPLICATE", existing.packetId());
            return rejected("EVENT_ID_CONFLICT");
        }
        if (EventPayloads.SENSOR.contains(event.type()) && (storedIntervalHit || tooClose(event, acceptedInPacket))) {
            return rejected("SENSOR_INTERVAL");
        }
        return new Decision("ACCEPTED", null, null);
    }

    private static boolean tooClose(ParsedEvent event, List<Prior> accepted) {
        for (Prior prior : accepted) {
            if (!prior.type().equals(event.type())) continue;
            long high = Math.max(prior.capturedAt(), event.capturedAt());
            long low = Math.min(prior.capturedAt(), event.capturedAt());
            if (high - low < EventLimits.SENSOR_INTERVAL_MS) return true;
        }
        return false;
    }

    private static Decision rejected(String reason) { return new Decision("REJECTED", reason, null); }
}

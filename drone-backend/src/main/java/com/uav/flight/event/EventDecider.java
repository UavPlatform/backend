package com.uav.flight.event;

/**
 * Fixed per-event result. A later gap fill does not rewrite it.
 * Capture time and identity are independent of arrival order.
 */
public final class EventDecider {
    public record Existing(String packetId, EventAttribution attribution) { }
    public record Decision(String outcome, String reason, String sourcePacketId) { }

    private EventDecider() { }

    public static Decision decide(ParsedEvent event, EventAttribution attribution, long now,
            Existing existing) {
        if (event.semanticReject() != null) return rejected(event.semanticReject());
        if (event.capturedAt() > now + EventLimits.CAPTURED_FUTURE_MS) return rejected("EVENT_TIME_WINDOW");
        if (existing != null) {
            if (existing.attribution().same(attribution)) return new Decision("DUPLICATE", "DUPLICATE", existing.packetId());
            return rejected("EVENT_ID_CONFLICT");
        }
        return new Decision("ACCEPTED", null, null);
    }

    private static Decision rejected(String reason) { return new Decision("REJECTED", reason, null); }
}

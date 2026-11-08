package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventDeciderTest {
    @Test
    void captureTimeAndDedupDoNotRequireAnAssignmentWindow() {
        long now = 1_710_000_000_000L;
        var old = event(now - 30L * 24 * 60 * 60 * 1000, "POSITION");
        assertThat(EventDecider.decide(old, attribution(old), now, null).outcome()).isEqualTo("ACCEPTED");
        var same = new EventDecider.Existing("packet-1", attribution(old));
        assertThat(EventDecider.decide(old, attribution(old), now, same).outcome()).isEqualTo("DUPLICATE");
        var changed = event(now, "POSITION");
        assertThat(EventDecider.decide(old, attribution(changed), now, same).reason()).isEqualTo("EVENT_ID_CONFLICT");
        var ahead = event(now + EventLimits.CAPTURED_FUTURE_MS + 1, "ACTUAL_FLYING_STATE");
        assertThat(EventDecider.decide(ahead, attribution(ahead), now, null).reason()).isEqualTo("EVENT_TIME_WINDOW");
    }

    private static ParsedEvent event(long capturedAt, String type) {
        return new ParsedEvent(1, "55555555-5555-4555-8555-555555555551", capturedAt, type, new byte[] {1}, null);
    }

    private static EventAttribution attribution(ParsedEvent event) {
        return new EventAttribution("11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
                "FC30SN1", "FLIGHT_CONTROLLER", "FC30", event.type(), event.capturedAt(), event.payloadCanonical());
    }
}

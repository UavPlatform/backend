package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventDeciderTest {
    @Test
    void eligibilityIsTheAssignmentIntervalAndIdentityStaysIndependentOfArrival() {
        long now = 1_710_000_000_000L;
        var open = AssignmentCaptureWindow.of(now - 60_000, null);
        var first = event(now, "POSITION");
        var close = event(now + 1_000, "POSITION");
        assertThat(EventDecider.decide(first, attribution(first), now, null, open).outcome()).isEqualTo("ACCEPTED");
        assertThat(EventDecider.decide(close, attribution(close), now, null, open).outcome()).isEqualTo("ACCEPTED");
        var same = new EventDecider.Existing("packet-1", attribution(first));
        assertThat(EventDecider.decide(first, attribution(first), now, same, open).outcome()).isEqualTo("DUPLICATE");
        assertThat(EventDecider.decide(first, attribution(close), now, same, open).reason()).isEqualTo("EVENT_ID_CONFLICT");
        var flying = event(now, "ACTUAL_FLYING_STATE");
        assertThat(EventDecider.decide(flying, attribution(flying), now, null, open).outcome()).isEqualTo("ACCEPTED");
        var early = event(now - 60_001, "ACTUAL_FLYING_STATE");
        assertThat(EventDecider.decide(early, attribution(early), now, null, open).reason()).isEqualTo("ASSIGNMENT_WINDOW");
        var ahead = event(now + EventLimits.CAPTURED_FUTURE_MS + 1, "ACTUAL_FLYING_STATE");
        assertThat(EventDecider.decide(ahead, attribution(ahead), now, null, open).reason()).isEqualTo("EVENT_TIME_WINDOW");
        long accepted = now - 8L * 24 * 60 * 60 * 1000;
        var historical = AssignmentCaptureWindow.of(accepted, accepted + 3_600_000);
        var lateUpload = event(accepted + 1_000, "POSITION");
        assertThat(EventDecider.decide(lateUpload, attribution(lateUpload), now, null, historical).outcome()).isEqualTo("ACCEPTED");
        var outside = event(accepted - 1, "POSITION");
        assertThat(EventDecider.decide(outside, attribution(outside), now, null, historical).reason()).isEqualTo("ASSIGNMENT_WINDOW");
        var atEnd = event(accepted + 3_600_000, "POSITION");
        assertThat(EventDecider.decide(atEnd, attribution(atEnd), now, null, historical).reason()).isEqualTo("ASSIGNMENT_WINDOW");
    }

    private static ParsedEvent event(long capturedAt, String type) {
        return new ParsedEvent(1, "55555555-5555-4555-8555-555555555551", capturedAt, type, new byte[] {1}, null);
    }

    private static EventAttribution attribution(ParsedEvent event) {
        return new EventAttribution("11111111-1111-4111-8111-111111111111", 1,
                "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
                "FC30SN1", "FLIGHT_CONTROLLER", "FC30", event.type(), event.capturedAt(), event.payloadCanonical());
    }
}

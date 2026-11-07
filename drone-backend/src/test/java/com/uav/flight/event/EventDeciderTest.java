package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventDeciderTest {
    @Test
    void keepsAcceptedStableAndRejectsOnlyTheLaterCloseSample() {
        long now = 1_710_000_000_000L;
        var first = event(0, "POSITION", null);
        var close = event(1, "POSITION", null);
        var spaced = event(2, "POSITION", null);
        var attribution = attribution(first);
        var accepted = EventDecider.decide(first, attribution, now, List.of(), null, false);
        assertThat(accepted.outcome()).isEqualTo("ACCEPTED");
        var prior = List.of(new EventDecider.Prior("POSITION", first.capturedAt()));
        assertThat(EventDecider.decide(close, attribution(close), now, prior, null, false).reason()).isEqualTo("SENSOR_INTERVAL");
        assertThat(EventDecider.decide(spaced, attribution(spaced), now, prior, null, false).outcome()).isEqualTo("ACCEPTED");
        var same = new EventDecider.Existing("packet-1", attribution);
        assertThat(EventDecider.decide(first, attribution, now, List.of(), same, true).outcome()).isEqualTo("DUPLICATE");
        var other = new EventDecider.Existing("packet-1", attribution(close));
        assertThat(EventDecider.decide(first, attribution, now, List.of(), other, false).reason()).isEqualTo("EVENT_ID_CONFLICT");
        assertThat(EventDecider.decide(event(3, "ACTUAL_FLYING_STATE", null), attribution(event(3, "ACTUAL_FLYING_STATE", null)),
                now, List.of(new EventDecider.Prior("ACTUAL_FLYING_STATE", now)), null, true).outcome()).isEqualTo("ACCEPTED");
    }

    private static ParsedEvent event(int index, String type, String reject) {
        long captured = 1_710_000_000_000L + index * 1_000L;
        if (index == 2) captured = 1_710_000_000_000L + 2_000L;
        return new ParsedEvent(index, "55555555-5555-4555-8555-55555555555" + index, captured, type, new byte[] {1}, reject);
    }

    private static EventAttribution attribution(ParsedEvent event) {
        return new EventAttribution("11111111-1111-4111-8111-111111111111", 1,
                "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
                "FC30SN1", "FLIGHT_CONTROLLER", "FC30", event.type(), event.capturedAt(), event.payloadCanonical());
    }
}

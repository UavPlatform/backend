package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventLimitsTest {
    @Test
    void unverifiedSpanIsNotALifetimePacketCap() {
        int span = EventLimits.UNVERIFIED_SPAN;
        assertThat(span).isEqualTo(2_000);
        assertThat(EventLimits.admitsUnverified(0, 0, span, span)).isTrue();
        assertThat(EventLimits.admitsUnverified(0, 0, span + 1L, span)).isFalse();
        assertThat(EventLimits.admitsUnverified(0, 0, EventLimits.MAX_HEIGHT, span)).isFalse();
        assertThat(EventLimits.admitsUnverified(0, span, 1, span)).isTrue();
        assertThat(EventLimits.admitsUnverified(0, span, span + 1L, span)).isFalse();
        assertThat(EventLimits.admitsUnverified(span, span, span + span, span)).isTrue();
        assertThat(EventLimits.admitsUnverified(span, span, span + span + 1L, span)).isFalse();
        assertThat(EventLimits.RATE_LIMIT).isEqualTo(600);
    }
}

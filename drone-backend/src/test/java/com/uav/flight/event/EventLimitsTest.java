package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventLimitsTest {
    @Test
    void capStillAdmitsAHoleInsideTheReceivedRange() {
        int max = EventLimits.MAX_PACKETS_PER_CHAIN;
        assertThat(max).isEqualTo(2_000);
        assertThat(EventLimits.admitsAnotherPacket(2_000, 2_001, 1, max)).isTrue();
        assertThat(EventLimits.admitsAnotherPacket(2_000, 2_001, 2_001, max)).isTrue();
        assertThat(EventLimits.admitsAnotherPacket(2_000, 2_001, 2_002, max)).isFalse();
        assertThat(EventLimits.admitsAnotherPacket(1_999, 1_999, 2_000, max)).isTrue();
    }
}

package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentCaptureWindowTest {
    @Test
    void closedRoundIsHalfOpenAndOpenRoundHasNoClockExpansion() {
        long accepted = 1_700_000_000_000L;
        long ended = accepted + 60_000;
        var closed = AssignmentCaptureWindow.of(accepted, ended);
        assertThat(closed.contains(accepted)).isTrue();
        assertThat(closed.contains(accepted - 1)).isFalse();
        assertThat(closed.contains(ended - 1)).isTrue();
        assertThat(closed.contains(ended)).isFalse();
        assertThat(closed.contains(ended + 60_000)).isFalse();
        var open = AssignmentCaptureWindow.of(accepted, null);
        assertThat(open.contains(accepted)).isTrue();
        assertThat(open.contains(accepted - 1)).isFalse();
        assertThat(open.contains(accepted + 8L * 24 * 60 * 60 * 1000)).isTrue();
    }
}

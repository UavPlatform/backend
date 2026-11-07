package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentCaptureWindowTest {
    @Test
    void closedRoundUsesEndedAtAndOpenRoundUsesTheServerClock() {
        LocalDateTime accepted = LocalDateTime.of(2026, 11, 7, 9, 0);
        LocalDateTime ended = accepted.plusMinutes(20);
        long acceptedMs = AssignmentCaptureWindow.epoch(accepted);
        long endedMs = AssignmentCaptureWindow.epoch(ended);
        var closed = AssignmentCaptureWindow.of(accepted, ended, endedMs + 3_600_000);
        assertThat(closed.contains(acceptedMs)).isTrue();
        assertThat(closed.contains(acceptedMs - EventLimits.CAPTURED_FUTURE_MS)).isTrue();
        assertThat(closed.contains(acceptedMs - EventLimits.CAPTURED_FUTURE_MS - 1)).isFalse();
        assertThat(closed.contains(endedMs + EventLimits.CAPTURED_FUTURE_MS)).isTrue();
        assertThat(closed.contains(endedMs + EventLimits.CAPTURED_FUTURE_MS + 1)).isFalse();
        var open = AssignmentCaptureWindow.of(accepted, null, acceptedMs + 60_000);
        assertThat(open.contains(acceptedMs + 60_000 + EventLimits.CAPTURED_FUTURE_MS)).isTrue();
        assertThat(open.contains(acceptedMs + 60_000 + EventLimits.CAPTURED_FUTURE_MS + 1)).isFalse();
    }
}

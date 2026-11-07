package com.uav.flight.event;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Authorization uses the assignment row that already exists for the packet's assignmentVersion.
 * {@code accepted_at} is written at claim and {@code ended_at} is written when that round ends
 * (release, shipper cancel, or completion). Both are {@code LocalDateTime.now()} values, so the
 * epoch conversion uses the JVM zone that produced them. Each edge allows the existing technical
 * clock skew {@link EventLimits#CAPTURED_FUTURE_MS}. An open round has no {@code ended_at}; its
 * upper bound is the server clock plus that skew. This is not a new business interval.
 */
public final class AssignmentCaptureWindow {
    private AssignmentCaptureWindow() { }

    public record Span(long startInclusive, long endInclusive) {
        public boolean contains(long capturedAt) {
            return capturedAt >= startInclusive && capturedAt <= endInclusive;
        }
    }

    public static Span of(LocalDateTime acceptedAt, LocalDateTime endedAt, long now) {
        long start = epoch(acceptedAt) - EventLimits.CAPTURED_FUTURE_MS;
        long end = endedAt == null ? now + EventLimits.CAPTURED_FUTURE_MS : epoch(endedAt) + EventLimits.CAPTURED_FUTURE_MS;
        return new Span(start, end);
    }

    static long epoch(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

package com.uav.flight.event;

/**
 * Eligibility is only the assignment row already stored for this assignmentVersion.
 * A closed round is {@code [acceptedAt, endedAt)} in absolute epoch milliseconds.
 * The end instant is not granted to the pilot who just released, so the next claim
 * cannot share it. An open round has no end. Clock skew is not applied here.
 */
public final class AssignmentCaptureWindow {
    private AssignmentCaptureWindow() { }

    public record Span(long startInclusive, Long endExclusive) {
        public boolean contains(long capturedAt) {
            if (capturedAt < startInclusive) return false;
            return endExclusive == null || capturedAt < endExclusive;
        }
    }

    public static Span of(long acceptedAtEpochMs, Long endedAtEpochMs) {
        return new Span(acceptedAtEpochMs, endedAtEpochMs);
    }
}

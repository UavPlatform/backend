package com.uav.flight.event;

import java.util.Arrays;

/** Equality for cross-packet eventId reuse. Different chain or payload is not the same fact. */
public record EventAttribution(
        String orderId,
        long assignmentVersion,
        String recordingSourceId,
        String chainId,
        String serialNumber,
        String serialSource,
        String modelCode,
        String type,
        long capturedAt,
        byte[] payloadCanonical) {

    public boolean same(EventAttribution other) {
        return other != null
                && orderId.equals(other.orderId)
                && assignmentVersion == other.assignmentVersion
                && recordingSourceId.equals(other.recordingSourceId)
                && chainId.equals(other.chainId)
                && serialNumber.equals(other.serialNumber)
                && serialSource.equals(other.serialSource)
                && modelCode.equals(other.modelCode)
                && type.equals(other.type)
                && capturedAt == other.capturedAt
                && Arrays.equals(payloadCanonical, other.payloadCanonical);
    }
}

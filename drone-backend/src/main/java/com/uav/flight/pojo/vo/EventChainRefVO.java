package com.uav.flight.pojo.vo;

/**
 * One chain bound to an order+airframe, whichever terminal recorded it.
 *
 * <p>Exists because chain identity includes recording_source_id, which a reinstalled or replaced device mints anew.
 * Without this view a client cannot discover the chain its own order and airframe already own, and every reinstall
 * would start a disconnected second chain. terminalMismatch marks rows recorded by a different recording source than
 * the caller asked about; such a chain cannot be continued by this terminal without an explicit decision.
 */
public record EventChainRefVO(
        String chainId,
        String recordingSourceId,
        boolean terminalMismatch,
        long contiguousHeight,
        String contiguousHash,
        long highestReceivedHeight,
        String integrity,
        long validationVersion,
        long projectionRevision) { }

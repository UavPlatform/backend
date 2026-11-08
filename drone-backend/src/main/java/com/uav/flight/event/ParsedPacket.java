package com.uav.flight.event;

import java.util.List;

/** A packet whose bytes already survived strict parsing, the draft allowlist, and the self-hash check. */
public record ParsedPacket(
        int schemaVersion,
        int hashVersion,
        String orderId,
        String recordingSourceId,
        String chainId,
        String serialNumber,
        String serialSource,
        String modelCode,
        String packetId,
        String prevPacketId,
        long height,
        byte[] prevChainHash,
        byte[] chainHash,
        byte[] canonicalUtf8,
        byte[] withoutChainHash,
        byte[] contentDigest,
        byte[] anchor,
        List<ParsedEvent> events) { }

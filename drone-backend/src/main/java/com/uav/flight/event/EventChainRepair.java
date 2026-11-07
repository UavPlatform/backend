package com.uav.flight.event;

import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * Continuation re-parses stored raw packet bytes. The client does not resend a successor body.
 * A broken link stops the contiguous prefix and leaves the stored packet and its receipt unchanged.
 */
public final class EventChainRepair {
    public record Successor(String chainId, long height, String packetId, byte[] rawBytes, byte[] storedChainHash) { }
    public record Tip(long contiguousHeight, byte[] contiguousHash, String tipPacketId, String integrity) { }

    private static final EventPacketSchema SCHEMA = new EventPacketSchema();

    private EventChainRepair() { }

    public static Tip walk(long contiguous, byte[] tipHash, String tipPacketId, List<Successor> ascending) {
        for (Successor successor : ascending) {
            if (successor.height() != contiguous + 1) break;
            ParsedPacket parsed;
            try {
                parsed = SCHEMA.parse(successor.rawBytes(), null);
            } catch (EventFormatException e) {
                return new Tip(contiguous, tipHash, tipPacketId, "FORKED");
            }
            boolean self = successor.packetId().equals(parsed.packetId())
                    && MessageDigest.isEqual(parsed.chainHash(), successor.storedChainHash());
            boolean link = tipHash != null
                    && parsed.chainId().equals(successor.chainId())
                    && parsed.height() == successor.height()
                    && Objects.equals(parsed.prevPacketId(), tipPacketId)
                    && MessageDigest.isEqual(parsed.prevChainHash(), tipHash);
            if (!self || !link) return new Tip(contiguous, tipHash, tipPacketId, "FORKED");
            contiguous = successor.height();
            tipHash = parsed.chainHash();
            tipPacketId = parsed.packetId();
        }
        return new Tip(contiguous, contiguous == 0 ? null : tipHash, tipPacketId, "OPEN");
    }
}

package com.uav.flight.event;

import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * Continuation uses the link columns checked when the packet was accepted.
 * Stored raw bytes stay immutable and are not loaded again to advance the prefix.
 */
public final class EventChainRepair {
    public record Link(long height, String packetId, String prevPacketId, byte[] prevChainHash, byte[] chainHash) { }

    public record Tip(long contiguousHeight, byte[] contiguousHash, String tipPacketId, String integrity, boolean fullPage) { }

    private EventChainRepair() { }

    public static Tip advance(long contiguous, byte[] tipHash, String tipPacketId, List<Link> ascending, int pageSize) {
        long height = contiguous;
        byte[] hash = tipHash;
        String tip = tipPacketId;
        for (Link link : ascending) {
            if (link.height() != height + 1) return tip(height, hash, tip, "OPEN", false);
            boolean linked = hash != null && link.prevChainHash() != null
                    && MessageDigest.isEqual(link.prevChainHash(), hash)
                    && Objects.equals(link.prevPacketId(), tip);
            if (!linked || link.chainHash() == null) return tip(height, hash, tip, "FORKED", false);
            height = link.height();
            hash = link.chainHash();
            tip = link.packetId();
        }
        return tip(height, hash, tip, "OPEN", pageSize > 0 && ascending.size() == pageSize);
    }

    private static Tip tip(long contiguous, byte[] hash, String packetId, String integrity, boolean fullPage) {
        return new Tip(contiguous, contiguous == 0 ? null : hash, packetId, integrity, fullPage);
    }
}

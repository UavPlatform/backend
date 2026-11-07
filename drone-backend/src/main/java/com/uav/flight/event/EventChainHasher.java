package com.uav.flight.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Draft chain formula. The domain strings are ASCII, so UTF-8 is a byte copy.
 * H0 = SHA256(UTF8("UAV-EVENT-ANCHOR-v1\n") || JCS(chainIdentity))
 * Hn = SHA256(UTF8("UAV-EVENT-PACKET-v1\n") || raw32(prevChainHash) || C)
 * contentDigest = SHA256(JCS(full packet, including chainHash))
 */
public final class EventChainHasher {
    private EventChainHasher() { }

    public static byte[] anchor(byte[] identityCanonical) {
        return sha256(EventLimits.ANCHOR_DOMAIN.getBytes(StandardCharsets.UTF_8), identityCanonical);
    }

    public static byte[] packet(byte[] previousHash, byte[] withoutChainHash) {
        if (previousHash == null || previousHash.length != 32) {
            throw new IllegalArgumentException("previous hash must be 32 bytes");
        }
        return sha256(EventLimits.PACKET_DOMAIN.getBytes(StandardCharsets.UTF_8), previousHash, withoutChainHash);
    }

    public static byte[] content(byte[] fullCanonical) {
        return sha256(fullCanonical);
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) digest.update(part);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

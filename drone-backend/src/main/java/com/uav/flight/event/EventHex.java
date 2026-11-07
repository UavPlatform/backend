package com.uav.flight.event;

/** Lowercase hex only. Uppercase is rejected rather than rewritten. */
public final class EventHex {
    private EventHex() { }

    public static String encode(byte[] raw) {
        var out = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    /** @return exactly {@code bytes} decoded, or null when the token is not lowercase hex of that width */
    public static byte[] decode(String hex, int bytes) {
        if (hex == null || hex.length() != bytes * 2) return null;
        var out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            int hi = nibble(hex.charAt(i * 2));
            int lo = nibble(hex.charAt(i * 2 + 1));
            if ((hi | lo) < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int nibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }
}

package com.uav.server.util;

import java.util.Locale;

/**
 * Canonical drone serial (机身号): trimmed and upper-cased. Used for admin registration, djifly-reported
 * connection / executed-order serials and every lookup by serial, so the same airframe always matches.
 */
public final class SerialNumbers {
    public static final int MAX_LENGTH = 64;
    private SerialNumbers() { }

    /** @return the canonical serial, or {@code null} for {@code null} input. */
    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }
}

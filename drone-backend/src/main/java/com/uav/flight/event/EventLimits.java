package com.uav.flight.event;

/**
 * Technical bounds for the unfrozen event-packet draft. These are not prices, retention periods,
 * or verified DJI SDK ranges.
 */
public final class EventLimits {
    public static final int SCHEMA_VERSION = 2;
    public static final int HASH_VERSION = 2;
    public static final int RECEIPT_VERSION = 1;
    public static final int MAX_BODY_BYTES = 262_144;
    public static final int MAX_EVENTS = 500;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_STRING_CHARS = 1024;
    public static final int MAX_OBJECT_KEYS = 32;
    public static final int MAX_ARRAY = 500;
    public static final long MAX_HEIGHT = 1_000_000L;
    /** How far a new height may sit ahead of the verified prefix. Not a lifetime packet count. */
    public static final int UNVERIFIED_SPAN = 2_000;
    /** Metadata rows read per repair step. The walk repeats until the suffix stops. */
    public static final int REPAIR_PAGE = 64;

    /**
     * A height inside the already received range is a reserved hole and stays fillable.
     * A height above that range is accepted only when the unverified span can cover it.
     */
    public static boolean admitsUnverified(long contiguousHeight, long highestReceivedHeight, long height, int span) {
        if (span < 1 || height < 1) return false;
        if (highestReceivedHeight >= height) return true;
        return height - contiguousHeight <= span;
    }

    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    public static final long CAPTURED_FUTURE_MS = 10L * 60 * 1000L;
    public static final int RECON_MAX_SPAN = 500;
    public static final int RECON_MAX_LIMIT = 200;
    public static final int RECON_MAX_PREFIX = 64;
    public static final int UPLOAD_MISSING_LIMIT = 50;
    public static final int RATE_LIMIT = 600;
    public static final int RATE_WINDOW_SECONDS = 60;
    public static final String DURABILITY = "RAW_AND_RESULTS_COMMITTED";
    public static final String ANCHOR_DOMAIN = "UAV-EVENT-ANCHOR-v2\n";
    public static final String PACKET_DOMAIN = "UAV-EVENT-PACKET-v2\n";

    private EventLimits() { }
}

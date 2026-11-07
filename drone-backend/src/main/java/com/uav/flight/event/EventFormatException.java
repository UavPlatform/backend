package com.uav.flight.event;

/** Parser or draft-schema failure. The message is a stable token and does not echo the body. */
public final class EventFormatException extends RuntimeException {
    public enum Kind { MALFORMED, HASH }

    private final Kind kind;

    private EventFormatException(Kind kind, String token) {
        super(token);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    public static EventFormatException malformed(String token) {
        return new EventFormatException(Kind.MALFORMED, token);
    }

    public static EventFormatException hash(String token) {
        return new EventFormatException(Kind.HASH, token);
    }
}

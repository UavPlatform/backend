package com.uav.flight.event;

import java.util.regex.Pattern;

/** Identity tokens are exact. They are not trimmed, case-folded, or Unicode-normalized. */
public final class EventTokens {
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern SERIAL_OR_MODEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SOURCE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private EventTokens() { }

    public static boolean uuid(String value) { return value != null && UUID.matcher(value).matches(); }

    public static boolean serial(String value) {
        return value != null && value.length() <= 64 && SERIAL_OR_MODEL.matcher(value).matches();
    }

    public static boolean model(String value) {
        return value != null && value.length() <= 128 && SERIAL_OR_MODEL.matcher(value).matches();
    }

    public static boolean source(String value) { return value != null && SOURCE.matcher(value).matches(); }

    public static boolean operation(String value) { return source(value); }
}

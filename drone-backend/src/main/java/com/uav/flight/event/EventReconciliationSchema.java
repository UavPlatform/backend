package com.uav.flight.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Draft reconciliation body. Every key is required; null is not the same as a missing key. */
public final class EventReconciliationSchema {
    private static final Set<String> ROOT = Set.of(
            "schemaVersion", "chainId", "validationVersion", "fromHeight", "toHeight",
            "cursor", "missingCursor", "limit", "prefix");
    private static final Set<String> PREFIX = Set.of("height", "chainHash");

    public Request parse(byte[] raw) {
        if (!(JsonCanon.parse(raw) instanceof JsonValue.Obj root) || !root.fields().keySet().equals(ROOT)) {
            throw EventFormatException.malformed("SCHEMA_FIELDS");
        }
        var fields = root.fields();
        if (!(fields.get("schemaVersion") instanceof JsonValue.Int version) || version.value() != EventLimits.SCHEMA_VERSION) {
            throw EventFormatException.malformed("SCHEMA_TYPE");
        }
        if (!(fields.get("chainId") instanceof JsonValue.Str chain) || !EventTokens.uuid(chain.text())) {
            throw EventFormatException.malformed("SCHEMA_IDENTITY");
        }
        Long validationVersion = optionalPositive(fields.get("validationVersion"));
        long from = requiredPositive(fields.get("fromHeight"));
        long to = requiredPositive(fields.get("toHeight"));
        if (to < from || to - from + 1 > EventLimits.RECON_MAX_SPAN) throw EventFormatException.malformed("LIMIT");
        int limit = limit(fields.get("limit"));
        return new Request(chain.text(), validationVersion, from, to, optionalPositive(fields.get("cursor")),
                optionalPositive(fields.get("missingCursor")), limit, prefix(fields.get("prefix")));
    }

    private static List<Request.Prefix> prefix(JsonValue value) {
        if (value instanceof JsonValue.Nil) return null;
        if (!(value instanceof JsonValue.Arr array) || array.items().size() > EventLimits.RECON_MAX_PREFIX) {
            throw EventFormatException.malformed("SCHEMA_TYPE");
        }
        var entries = new ArrayList<Request.Prefix>();
        long previous = 0;
        for (JsonValue item : array.items()) {
            if (!(item instanceof JsonValue.Obj object) || !object.fields().keySet().equals(PREFIX)) {
                throw EventFormatException.malformed("SCHEMA_FIELDS");
            }
            long height = requiredPositive(object.fields().get("height"));
            if (height <= previous || height > EventLimits.MAX_HEIGHT) throw EventFormatException.malformed("SCHEMA_HEIGHT");
            if (!(object.fields().get("chainHash") instanceof JsonValue.Str hash)) throw EventFormatException.malformed("SCHEMA_IDENTITY");
            byte[] raw = EventHex.decode(hash.text(), 32);
            if (raw == null) throw EventFormatException.malformed("SCHEMA_IDENTITY");
            entries.add(new Request.Prefix(height, raw));
            previous = height;
        }
        return List.copyOf(entries);
    }

    private static int limit(JsonValue value) {
        if (!(value instanceof JsonValue.Int number)) throw EventFormatException.malformed("SCHEMA_TYPE");
        long raw = number.value();
        if (raw < 1 || raw > EventLimits.RECON_MAX_LIMIT) throw EventFormatException.malformed("LIMIT");
        return (int) raw;
    }

    private static long requiredPositive(JsonValue value) {
        if (!(value instanceof JsonValue.Int number) || number.value() < 1 || number.value() > EventLimits.MAX_SAFE_INTEGER) {
            throw EventFormatException.malformed("SCHEMA_TYPE");
        }
        return number.value();
    }

    private static Long optionalPositive(JsonValue value) {
        if (value instanceof JsonValue.Nil) return null;
        return requiredPositive(value);
    }

    public record Request(String chainId, Long validationVersion, long fromHeight, long toHeight, Long cursor,
            Long missingCursor, int limit, List<Prefix> prefix) {
        public record Prefix(long height, byte[] chainHash) { }
    }
}

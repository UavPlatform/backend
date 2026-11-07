package com.uav.flight.event;

import java.util.Map;
import java.util.Set;

/**
 * Draft payload allowlists. Scales are contract units the client must encode; they are not verified DJI SDK mappings.
 * A missing sensor reading is a missing event, never a zero or false filled in here.
 * FC serial and product serial are different {@code serialSource} tokens and are not aliased.
 */
public final class EventPayloads {
    static final Set<String> SENSOR = Set.of("POSITION", "ALTITUDE", "VELOCITY", "BATTERY");
    private static final Set<String> KNOWN = Set.of(
            "POSITION", "ALTITUDE", "VELOCITY", "BATTERY",
            "ACTUAL_FLYING_STATE", "ACTUAL_MOTOR_STATE",
            "OPERATION_INTENT", "OPERATION_RESULT", "RECORDING_CUTOFF");

    private EventPayloads() { }

    /** @return null when the event may be accepted, otherwise a fixed per-event reason */
    public static String validate(String type, JsonValue payload, String eventId) {
        if (!(payload instanceof JsonValue.Obj object)) return "PAYLOAD_INVALID";
        if (!KNOWN.contains(type)) return "UNKNOWN_EVENT_TYPE";
        Map<String, JsonValue> fields = object.fields();
        return switch (type) {
            case "POSITION" -> integers(fields, "latitudeDegE7", -900_000_000L, 900_000_000L,
                    "longitudeDegE7", -1_800_000_000L, 1_800_000_000L);
            case "ALTITUDE" -> one(fields, "altitudeMm", -10_000_000L, 100_000_000L);
            case "VELOCITY" -> one(fields, "speedMmps", 0L, 2_000_000L);
            case "BATTERY" -> one(fields, "chargePermille", 0L, 1_000L);
            case "ACTUAL_FLYING_STATE" -> flag(fields, "flying");
            case "ACTUAL_MOTOR_STATE" -> flag(fields, "motorOn");
            case "OPERATION_INTENT" -> operation(fields, false);
            case "OPERATION_RESULT" -> operation(fields, true);
            case "RECORDING_CUTOFF" -> cutoff(fields, eventId);
            default -> "UNKNOWN_EVENT_TYPE";
        };
    }

    private static String integers(Map<String, JsonValue> fields, String a, long aMin, long aMax,
            String b, long bMin, long bMax) {
        if (!fields.keySet().equals(Set.of(a, b))) return "PAYLOAD_INVALID";
        if (!inRange(fields.get(a), aMin, aMax) || !inRange(fields.get(b), bMin, bMax)) return "PAYLOAD_INVALID";
        return null;
    }

    private static String one(Map<String, JsonValue> fields, String name, long min, long max) {
        if (!fields.keySet().equals(Set.of(name)) || !inRange(fields.get(name), min, max)) return "PAYLOAD_INVALID";
        return null;
    }

    private static String flag(Map<String, JsonValue> fields, String name) {
        if (!fields.keySet().equals(Set.of(name)) || !(fields.get(name) instanceof JsonValue.Bool)) return "PAYLOAD_INVALID";
        return null;
    }

    private static String operation(Map<String, JsonValue> fields, boolean withResult) {
        var expected = withResult ? Set.of("operation", "clientNonce", "succeeded") : Set.of("operation", "clientNonce");
        if (!fields.keySet().equals(expected)) return "PAYLOAD_INVALID";
        if (!(fields.get("operation") instanceof JsonValue.Str op) || !EventTokens.operation(op.text())) return "PAYLOAD_INVALID";
        if (!(fields.get("clientNonce") instanceof JsonValue.Str nonce) || !EventTokens.uuid(nonce.text())) return "PAYLOAD_INVALID";
        if (withResult && !(fields.get("succeeded") instanceof JsonValue.Bool)) return "PAYLOAD_INVALID";
        return null;
    }

    private static String cutoff(Map<String, JsonValue> fields, String eventId) {
        if (!fields.keySet().equals(Set.of("cutoffId"))) return "PAYLOAD_INVALID";
        if (!(fields.get("cutoffId") instanceof JsonValue.Str id) || !EventTokens.uuid(id.text())) return "PAYLOAD_INVALID";
        if (id.text().equals(eventId)) return "PAYLOAD_INVALID";
        return null;
    }

    private static boolean inRange(JsonValue value, long min, long max) {
        return value instanceof JsonValue.Int number && number.value() >= min && number.value() <= max;
    }
}

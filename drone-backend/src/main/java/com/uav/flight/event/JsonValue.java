package com.uav.flight.event;

import java.util.List;
import java.util.Map;

/** Integer-profile JSON. Numbers are exact safe integers, never binary64 doubles. */
public sealed interface JsonValue {
    record Obj(Map<String, JsonValue> fields) implements JsonValue { }
    record Arr(List<JsonValue> items) implements JsonValue { }
    record Str(String text) implements JsonValue { }
    record Int(long value) implements JsonValue { }
    record Bool(boolean value) implements JsonValue { }
    record Nil() implements JsonValue { }
}

package com.uav.flight.event;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draft packet allowlist. chainIdentity is exactly:
 * assignmentVersion, chainId, hashVersion, modelCode, orderId, recordingSourceId,
 * schemaVersion, serialNumber, serialSource.
 * schemaVersion and hashVersion are inside the anchor on purpose. Display names are not accepted.
 * A missing key is not the same as a present null. The first packet must send prevPacketId: null.
 */
public final class EventPacketSchema {
    static final Set<String> ROOT = Set.of(
            "schemaVersion", "hashVersion", "orderId", "assignmentVersion", "recordingSourceId", "chainId",
            "serialNumber", "serialSource", "modelCode", "packetId", "prevPacketId", "height",
            "prevChainHash", "chainHash", "events");
    private static final Set<String> EVENT = Set.of("eventId", "capturedAt", "type", "payload");

    public ParsedPacket parse(byte[] raw, String pathOrderId) {
        if (!(JsonCanon.parse(raw) instanceof JsonValue.Obj root)) throw EventFormatException.malformed("SCHEMA_TYPE");
        if (!root.fields().keySet().equals(ROOT)) throw EventFormatException.malformed("SCHEMA_FIELDS");
        Map<String, JsonValue> fields = root.fields();
        int schemaVersion = exactVersion(fields.get("schemaVersion"), EventLimits.SCHEMA_VERSION);
        int hashVersion = exactVersion(fields.get("hashVersion"), EventLimits.HASH_VERSION);
        String orderId = text(fields.get("orderId"), EventTokens::uuid, "SCHEMA_ORDER");
        if (pathOrderId != null && !pathOrderId.equals(orderId)) throw EventFormatException.malformed("SCHEMA_ORDER");
        long assignmentVersion = positive(fields.get("assignmentVersion"), "SCHEMA_TYPE");
        String recordingSourceId = text(fields.get("recordingSourceId"), EventTokens::uuid, "SCHEMA_IDENTITY");
        String chainId = text(fields.get("chainId"), EventTokens::uuid, "SCHEMA_IDENTITY");
        String serialNumber = text(fields.get("serialNumber"), EventTokens::serial, "SCHEMA_IDENTITY");
        String serialSource = text(fields.get("serialSource"), EventTokens::source, "SCHEMA_IDENTITY");
        String modelCode = text(fields.get("modelCode"), EventTokens::model, "SCHEMA_IDENTITY");
        String packetId = text(fields.get("packetId"), EventTokens::uuid, "SCHEMA_IDENTITY");
        long height = positive(fields.get("height"), "SCHEMA_HEIGHT");
        if (height > EventLimits.MAX_HEIGHT) throw EventFormatException.malformed("SCHEMA_HEIGHT");
        String prevPacketId = previousPacket(fields.get("prevPacketId"), height);
        byte[] prevChainHash = hash(fields.get("prevChainHash"));
        byte[] claimed = hash(fields.get("chainHash"));
        List<ParsedEvent> events = events(fields.get("events"));
        byte[] identity = JsonCanon.canonical(identityOf(schemaVersion, hashVersion, orderId, assignmentVersion,
                recordingSourceId, chainId, serialNumber, serialSource, modelCode));
        byte[] anchor = EventChainHasher.anchor(identity);
        byte[] without = JsonCanon.canonical(JsonCanon.without(root, "chainHash"));
        byte[] chainHash = EventChainHasher.packet(prevChainHash, without);
        if (!MessageDigest.isEqual(chainHash, claimed)) throw EventFormatException.hash("HASH");
        if (height == 1 && !MessageDigest.isEqual(anchor, prevChainHash)) throw EventFormatException.hash("ANCHOR");
        byte[] full = JsonCanon.canonical(root);
        return new ParsedPacket(schemaVersion, hashVersion, orderId, assignmentVersion, recordingSourceId, chainId,
                serialNumber, serialSource, modelCode, packetId, prevPacketId, height, prevChainHash, chainHash,
                full, without, EventChainHasher.content(full), anchor, events);
    }

    private static JsonValue.Obj identityOf(int schemaVersion, int hashVersion, String orderId, long assignmentVersion,
            String recordingSourceId, String chainId, String serialNumber, String serialSource, String modelCode) {
        var fields = new LinkedHashMap<String, JsonValue>();
        fields.put("assignmentVersion", new JsonValue.Int(assignmentVersion));
        fields.put("chainId", new JsonValue.Str(chainId));
        fields.put("hashVersion", new JsonValue.Int(hashVersion));
        fields.put("modelCode", new JsonValue.Str(modelCode));
        fields.put("orderId", new JsonValue.Str(orderId));
        fields.put("recordingSourceId", new JsonValue.Str(recordingSourceId));
        fields.put("schemaVersion", new JsonValue.Int(schemaVersion));
        fields.put("serialNumber", new JsonValue.Str(serialNumber));
        fields.put("serialSource", new JsonValue.Str(serialSource));
        return new JsonValue.Obj(fields);
    }

    private static List<ParsedEvent> events(JsonValue value) {
        if (!(value instanceof JsonValue.Arr array) || array.items().isEmpty()) {
            throw EventFormatException.malformed("SCHEMA_EMPTY");
        }
        var seen = new java.util.HashSet<String>();
        var events = new ArrayList<ParsedEvent>();
        int ordinal = 0;
        for (JsonValue item : array.items()) {
            if (!(item instanceof JsonValue.Obj object) || !object.fields().keySet().equals(EVENT)) {
                throw EventFormatException.malformed("SCHEMA_FIELDS");
            }
            String eventId = text(object.fields().get("eventId"), EventTokens::uuid, "SCHEMA_IDENTITY");
            if (!seen.add(eventId)) throw EventFormatException.malformed("SCHEMA_DUPLICATE_EVENT");
            long capturedAt = nonNegative(object.fields().get("capturedAt"));
            if (!(object.fields().get("type") instanceof JsonValue.Str type)) throw EventFormatException.malformed("SCHEMA_TYPE");
            JsonValue payload = object.fields().get("payload");
            if (!(payload instanceof JsonValue.Obj)) throw EventFormatException.malformed("SCHEMA_TYPE");
            String reason = EventPayloads.validate(type.text(), payload, eventId);
            events.add(new ParsedEvent(ordinal++, eventId, capturedAt, type.text(), JsonCanon.canonical(payload), reason));
        }
        return List.copyOf(events);
    }

    private static String previousPacket(JsonValue value, long height) {
        if (height == 1) {
            if (!(value instanceof JsonValue.Nil)) throw EventFormatException.malformed("SCHEMA_HEIGHT");
            return null;
        }
        if (!(value instanceof JsonValue.Str text) || !EventTokens.uuid(text.text())) {
            throw EventFormatException.malformed("SCHEMA_HEIGHT");
        }
        return text.text();
    }

    private static int exactVersion(JsonValue value, int expected) {
        if (!(value instanceof JsonValue.Int number) || number.value() != expected) {
            throw EventFormatException.malformed("SCHEMA_TYPE");
        }
        return expected;
    }

    private static long positive(JsonValue value, String token) {
        if (!(value instanceof JsonValue.Int number) || number.value() < 1) throw EventFormatException.malformed(token);
        return number.value();
    }

    private static long nonNegative(JsonValue value) {
        if (!(value instanceof JsonValue.Int number) || number.value() < 0) throw EventFormatException.malformed("SCHEMA_TYPE");
        return number.value();
    }

    private static byte[] hash(JsonValue value) {
        if (!(value instanceof JsonValue.Str text)) throw EventFormatException.malformed("SCHEMA_IDENTITY");
        byte[] raw = EventHex.decode(text.text(), 32);
        if (raw == null) throw EventFormatException.malformed("SCHEMA_IDENTITY");
        return raw;
    }

    private static String text(JsonValue value, java.util.function.Predicate<String> pattern, String token) {
        if (!(value instanceof JsonValue.Str text) || !pattern.test(text.text())) throw EventFormatException.malformed(token);
        return text.text();
    }
}

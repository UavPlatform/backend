package com.uav.flight.event;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds draft packets with the production hasher so HTTP tests send bytes the server can recompute. */
public final class EventPacketSamples {
    private EventPacketSamples() { }

    public record Identity(String orderId, long assignmentVersion, String recordingSourceId, String chainId,
            String serialNumber, String serialSource, String modelCode) { }

    public record Ev(String eventId, long capturedAt, String type, JsonValue payload) { }

    public static JsonValue position(long latitudeDegE7, long longitudeDegE7) {
        return obj(Map.of("latitudeDegE7", new JsonValue.Int(latitudeDegE7), "longitudeDegE7", new JsonValue.Int(longitudeDegE7)));
    }

    public static JsonValue altitude(long altitudeMm) { return obj(Map.of("altitudeMm", new JsonValue.Int(altitudeMm))); }

    public static JsonValue flying(boolean value) { return obj(Map.of("flying", new JsonValue.Bool(value))); }

    public static byte[] anchor(Identity identity) {
        return EventChainHasher.anchor(JsonCanon.canonical(identityOf(identity)));
    }

    public static byte[] packet(Identity identity, String packetId, String prevPacketId, long height, byte[] prevHash, List<Ev> events) {
        var fields = new LinkedHashMap<String, JsonValue>();
        fields.put("schemaVersion", new JsonValue.Int(1));
        fields.put("hashVersion", new JsonValue.Int(1));
        fields.put("orderId", new JsonValue.Str(identity.orderId()));
        fields.put("assignmentVersion", new JsonValue.Int(identity.assignmentVersion()));
        fields.put("recordingSourceId", new JsonValue.Str(identity.recordingSourceId()));
        fields.put("chainId", new JsonValue.Str(identity.chainId()));
        fields.put("serialNumber", new JsonValue.Str(identity.serialNumber()));
        fields.put("serialSource", new JsonValue.Str(identity.serialSource()));
        fields.put("modelCode", new JsonValue.Str(identity.modelCode()));
        fields.put("packetId", new JsonValue.Str(packetId));
        fields.put("prevPacketId", prevPacketId == null ? new JsonValue.Nil() : new JsonValue.Str(prevPacketId));
        fields.put("height", new JsonValue.Int(height));
        fields.put("prevChainHash", new JsonValue.Str(EventHex.encode(prevHash)));
        fields.put("chainHash", new JsonValue.Str("0".repeat(64)));
        fields.put("events", new JsonValue.Arr(events.stream().map(EventPacketSamples::event).toList()));
        byte[] without = JsonCanon.canonical(JsonCanon.without(new JsonValue.Obj(fields), "chainHash"));
        fields.put("chainHash", new JsonValue.Str(EventHex.encode(EventChainHasher.packet(prevHash, without))));
        return JsonCanon.canonical(new JsonValue.Obj(fields));
    }

    public static byte[] first(Identity identity, String packetId, List<Ev> events) {
        return packet(identity, packetId, null, 1, anchor(identity), events);
    }

    public static byte[] spaced(byte[] canonical) {
        var text = new String(canonical, StandardCharsets.UTF_8);
        return text.replaceFirst("\\{", "{\n").getBytes(StandardCharsets.UTF_8);
    }

    private static JsonValue event(Ev event) {
        var fields = new LinkedHashMap<String, JsonValue>();
        fields.put("eventId", new JsonValue.Str(event.eventId()));
        fields.put("capturedAt", new JsonValue.Int(event.capturedAt()));
        fields.put("type", new JsonValue.Str(event.type()));
        fields.put("payload", event.payload());
        return new JsonValue.Obj(fields);
    }

    private static JsonValue.Obj identityOf(Identity identity) {
        var fields = new LinkedHashMap<String, JsonValue>();
        fields.put("assignmentVersion", new JsonValue.Int(identity.assignmentVersion()));
        fields.put("chainId", new JsonValue.Str(identity.chainId()));
        fields.put("hashVersion", new JsonValue.Int(1));
        fields.put("modelCode", new JsonValue.Str(identity.modelCode()));
        fields.put("orderId", new JsonValue.Str(identity.orderId()));
        fields.put("recordingSourceId", new JsonValue.Str(identity.recordingSourceId()));
        fields.put("schemaVersion", new JsonValue.Int(1));
        fields.put("serialNumber", new JsonValue.Str(identity.serialNumber()));
        fields.put("serialSource", new JsonValue.Str(identity.serialSource()));
        return new JsonValue.Obj(fields);
    }

    private static JsonValue obj(Map<String, JsonValue> fields) { return new JsonValue.Obj(new LinkedHashMap<>(fields)); }
}

package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPacketSchemaTest {
    private static final EventPacketSamples.Identity ID = new EventPacketSamples.Identity(
            "11111111-1111-4111-8111-111111111111", 1,
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
    private final EventPacketSchema schema = new EventPacketSchema();

    @Test
    void acceptsAFirstPacketAndRejectsStructuralDuplicates() {
        byte[] raw = packet();
        var parsed = schema.parse(raw, ID.orderId());
        assertThat(parsed.height()).isEqualTo(1);
        assertThat(parsed.prevPacketId()).isNull();
        assertThat(parsed.events()).hasSize(1);
        assertThat(parsed.events().getFirst().semanticReject()).isNull();
        byte[] two = EventPacketSamples.first(ID, "44444444-4444-4444-8444-444444444444", List.of(event(), event()));
        assertThatThrownBy(() -> schema.parse(two, ID.orderId())).isInstanceOf(EventFormatException.class).hasMessage("SCHEMA_DUPLICATE_EVENT");
    }

    @Test
    void missingPreviousPacketIsNotNullAndV1IsRejected() {
        String json = new String(packet(), StandardCharsets.UTF_8).replace("\"prevPacketId\":null", "\"prevPacketId\":0");
        assertThatThrownBy(() -> schema.parse(json.getBytes(StandardCharsets.UTF_8), ID.orderId()))
                .isInstanceOf(EventFormatException.class);
        String removed = json.replace(",\"prevPacketId\":0", "");
        assertThatThrownBy(() -> schema.parse(removed.getBytes(StandardCharsets.UTF_8), ID.orderId())).hasMessage("SCHEMA_FIELDS");
        String oldVersion = new String(packet(), StandardCharsets.UTF_8).replace("schemaVersion" + (char) 34 + ":2", "schemaVersion" + (char) 34 + ":1");
        assertThatThrownBy(() -> schema.parse(oldVersion.getBytes(StandardCharsets.UTF_8), ID.orderId())).hasMessage("SCHEMA_TYPE");
    }

    @Test
    void outOfRangePayloadRejectsOnlyThatEvent() {
        var bad = new EventPacketSamples.Ev("55555555-5555-4555-8555-555555555555", 1_710_000_000_000L,
                "POSITION", EventPacketSamples.position(900_000_001L, 1L));
        var parsed = schema.parse(EventPacketSamples.first(ID, "44444444-4444-4444-8444-444444444444", List.of(bad)), ID.orderId());
        assertThat(parsed.events().getFirst().semanticReject()).isEqualTo("PAYLOAD_INVALID");
    }

    @Test
    void hashMismatchStoresNothingAtTheSchemaLayer() {
        String text = new String(packet(), StandardCharsets.UTF_8);
        int at = text.indexOf("\"chainHash\":\"") + "\"chainHash\":\"".length();
        char[] chars = text.toCharArray();
        chars[at] = chars[at] == '0' ? '1' : '0';
        assertThatThrownBy(() -> schema.parse(new String(chars).getBytes(StandardCharsets.UTF_8), ID.orderId()))
                .isInstanceOf(EventFormatException.class)
                .extracting(ex -> ((EventFormatException) ex).kind()).isEqualTo(EventFormatException.Kind.HASH);
    }

    private static byte[] packet() {
        return EventPacketSamples.first(ID, "44444444-4444-4444-8444-444444444444", List.of(event()));
    }

    private static EventPacketSamples.Ev event() {
        return new EventPacketSamples.Ev("55555555-5555-4555-8555-555555555555", 1_710_000_000_000L,
                "POSITION", EventPacketSamples.position(282_000_000L, 1_130_000_000L));
    }
}

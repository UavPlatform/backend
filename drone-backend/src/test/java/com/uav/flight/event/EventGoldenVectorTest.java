package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These vectors are a backend proposal for Kotlin to re-verify.
 * djifly PR 19 only checked a synthetic fixture, not this identity or these payloads.
 */
class EventGoldenVectorTest {
    private static final EventPacketSamples.Identity ID = new EventPacketSamples.Identity(
            "11111111-1111-4111-8111-111111111111", 1,
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
    private static final String IDENTITY = "{\"chainId\":\"33333333-3333-4333-8333-333333333333\","
            + "\"hashVersion\":2,\"modelCode\":\"FC30\",\"orderId\":\"11111111-1111-4111-8111-111111111111\","
            + "\"recordingSourceId\":\"22222222-2222-4222-8222-222222222222\",\"schemaVersion\":2,"
            + "\"serialNumber\":\"FC30SN1\",\"serialSource\":\"FLIGHT_CONTROLLER\"}";
    private static final Path FILE = Path.of("../spec/event-contract/golden-vectors.json");
    private static final String NON_ASCII_INPUT = "7b227a223a312c2273223a22c3a92fe280a8e280a9227d";
    private static final String NON_ASCII_CANONICAL = "7b2273223a22c3a92fe280a8e280a9222c227a223a317d";

    @Test
    void proposedVectorsMatchAnIndependentSha256() throws Exception {
        byte[] identity = IDENTITY.getBytes(StandardCharsets.UTF_8);
        assertThat(JsonCanon.canonical(JsonCanon.parse(identity))).isEqualTo(identity);
        byte[] anchor = sha(ascii("UAV-EVENT-ANCHOR-v2\n"), identity);
        assertThat(EventChainHasher.anchor(identity)).isEqualTo(anchor);
        byte[] first = EventPacketSamples.first(ID, "44444444-4444-4444-8444-444444444444", List.of(position(
                "55555555-5555-4555-8555-555555555555", 1_710_000_000_000L, 282_000_000L, 1_130_000_000L)));
        var parsed = new EventPacketSchema().parse(first, ID.orderId());
        assertThat(parsed.anchor()).isEqualTo(anchor);
        byte[] chain = sha(ascii("UAV-EVENT-PACKET-v2\n"), parsed.prevChainHash(), parsed.withoutChainHash());
        byte[] digest = sha(parsed.canonicalUtf8());
        assertThat(parsed.chainHash()).isEqualTo(chain);
        assertThat(parsed.contentDigest()).isEqualTo(digest);
        assertThat(EventChainHasher.content(parsed.canonicalUtf8())).isEqualTo(digest);
        byte[] spaced = EventPacketSamples.spaced(first);
        var spacedParsed = new EventPacketSchema().parse(spaced, ID.orderId());
        assertThat(spacedParsed.contentDigest()).isEqualTo(digest);
        assertThat(EventChainHasher.sha256(spaced)).isNotEqualTo(digest);
        byte[] second = EventPacketSamples.packet(ID, "66666666-6666-4666-8666-666666666666", parsed.packetId(), 2,
                parsed.chainHash(), List.of(new EventPacketSamples.Ev("77777777-7777-4777-8777-777777777777",
                        1_710_000_003_000L, "ALTITUDE", EventPacketSamples.altitude(12_345L))));
        var secondParsed = new EventPacketSchema().parse(second, ID.orderId());
        assertThat(secondParsed.chainHash()).isEqualTo(sha(ascii("UAV-EVENT-PACKET-v2\n"), parsed.chainHash(), secondParsed.withoutChainHash()));
        var other = new EventPacketSamples.Identity(ID.orderId(), 1, ID.recordingSourceId(),
                "88888888-8888-4888-8888-888888888888", "PRODUCTSN1", "PRODUCT", "FC30");
        byte[] otherRaw = EventPacketSamples.first(other, "99999999-9999-4999-8999-999999999999", List.of(position(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 1_710_000_000_000L, 1L, 2L)));
        var otherParsed = new EventPacketSchema().parse(otherRaw, ID.orderId());
        assertThat(otherParsed.anchor()).isNotEqualTo(anchor);
        var vectors = List.of(
                vector("height1-position", first, parsed),
                vector("height1-same-canonical-different-bytes", spaced, spacedParsed),
                vector("height2-altitude", second, secondParsed),
                vector("other-airframe-product-serial", otherRaw, otherParsed));
        String json = render(anchor, vectors);
        byte[] fixtureInput = EventHex.decode(NON_ASCII_INPUT, NON_ASCII_INPUT.length() / 2);
        assertThat(EventHex.encode(JsonCanon.canonical(JsonCanon.parse(fixtureInput)))).isEqualTo(NON_ASCII_CANONICAL);
        assertThat(FILE).exists();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(FILE)))
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        assertThatThrownBy(() -> JsonCanon.parse("{\"a\":01}".getBytes(StandardCharsets.UTF_8))).hasMessage("NUMBER");
    }

    private static Vector vector(String name, byte[] input, ParsedPacket parsed) {
        return new Vector(name, EventHex.encode(input), EventHex.encode(parsed.canonicalUtf8()),
                EventHex.encode(parsed.withoutChainHash()), EventHex.encode(parsed.anchor()),
                EventHex.encode(parsed.chainHash()), EventHex.encode(parsed.contentDigest()),
                EventHex.encode(EventChainHasher.sha256(input)));
    }

    private static String render(byte[] anchor, List<Vector> vectors) {
        var out = new StringBuilder();
        out.append("{\n  \"status\": \"draft-not-verified-by-kotlin\",\n");
        out.append("  \"note\": \"djifly PR 19 commit 96ea023 verified a synthetic fixture only. These vectors are the backend proposal.\",\n");
        out.append("  \"identityFields\": [\"chainId\", \"hashVersion\", \"modelCode\", \"orderId\", \"recordingSourceId\", \"schemaVersion\", \"serialNumber\", \"serialSource\"],\n");
        out.append("  \"identityCanonicalUtf8\": ").append(quote(IDENTITY)).append(",\n");
        out.append("  \"anchorHex\": \"").append(EventHex.encode(anchor)).append("\",\n");
        out.append("  \"vectors\": [\n");
        for (int i = 0; i < vectors.size(); i++) {
            Vector vector = vectors.get(i);
            out.append("    {\"name\": \"").append(vector.name).append("\", \"inputUtf8Hex\": \"").append(vector.input)
                    .append("\", \"canonicalUtf8Hex\": \"").append(vector.canonical)
                    .append("\", \"withoutChainHashHex\": \"").append(vector.without)
                    .append("\", \"anchorHex\": \"").append(vector.anchor)
                    .append("\", \"chainHashHex\": \"").append(vector.chain)
                    .append("\", \"contentDigestHex\": \"").append(vector.digest)
                    .append("\", \"rawSha256Hex\": \"").append(vector.raw).append("\"}");
            out.append(i + 1 == vectors.size() ? "\n" : ",\n");
        }
        out.append("  ],\n");
        out.append("  \"canonicalFixtures\": [\n");
        out.append("    {\"name\": \"non-ascii-line-separator\", \"inputUtf8Hex\": \"").append(NON_ASCII_INPUT)
                .append("\", \"canonicalUtf8Hex\": \"").append(NON_ASCII_CANONICAL).append("\"}\n");
        out.append("  ]\n}\n");
        return out.toString();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static EventPacketSamples.Ev position(String eventId, long capturedAt, long lat, long lon) {
        return new EventPacketSamples.Ev(eventId, capturedAt, "POSITION", EventPacketSamples.position(lat, lon));
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] sha(byte[]... parts) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (byte[] part : parts) digest.update(part);
        return digest.digest();
    }

    private record Vector(String name, String input, String canonical, String without, String anchor, String chain,
            String digest, String raw) { }
}

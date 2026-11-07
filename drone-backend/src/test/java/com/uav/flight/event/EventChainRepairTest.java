package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventChainRepairTest {
    @Test
    void advancesByStoredLinksAndStopsOnAForkWithoutReadingRawBytes() {
        var identity = new EventPacketSamples.Identity("11111111-1111-4111-8111-111111111111", 1,
                "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
                "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        byte[] first = EventPacketSamples.first(identity, "44444444-4444-4444-8444-444444444444", List.of(position("55555555-5555-4555-8555-555555555555")));
        var parsed = new EventPacketSchema().parse(first, identity.orderId());
        byte[] second = EventPacketSamples.packet(identity, "66666666-6666-4666-8666-666666666666",
                parsed.packetId(), 2, parsed.chainHash(), List.of(position("77777777-7777-4777-8777-777777777777")));
        var secondParsed = new EventPacketSchema().parse(second, identity.orderId());
        var page = EventChainRepair.advance(0, parsed.anchor(), null, List.of(link(parsed, null)), 1);
        assertThat(page.fullPage()).isTrue();
        assertThat(page.contiguousHeight()).isEqualTo(1);
        var linked = EventChainRepair.advance(page.contiguousHeight(), page.contiguousHash(), page.tipPacketId(),
                List.of(link(secondParsed, parsed.packetId())), 1);
        assertThat(linked.contiguousHeight()).isEqualTo(2);
        assertThat(linked.integrity()).isEqualTo("OPEN");
        var stopped = EventChainRepair.advance(1, parsed.chainHash(), parsed.packetId(), List.of(
                new EventChainRepair.Link(2, "99999999-9999-4999-8999-999999999999",
                        "88888888-8888-4888-8888-888888888888", new byte[32], new byte[32])), 1);
        assertThat(stopped.contiguousHeight()).isEqualTo(1);
        assertThat(stopped.integrity()).isEqualTo("FORKED");
        assertThat(stopped.fullPage()).isFalse();
    }

    private static EventChainRepair.Link link(ParsedPacket packet, String prevPacketId) {
        return new EventChainRepair.Link(packet.height(), packet.packetId(), prevPacketId, packet.prevChainHash(), packet.chainHash());
    }

    private static EventPacketSamples.Ev position(String eventId) {
        return new EventPacketSamples.Ev(eventId, 1_710_000_000_000L, "POSITION", EventPacketSamples.position(1, 2));
    }
}

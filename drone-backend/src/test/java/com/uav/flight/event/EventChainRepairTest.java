package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventChainRepairTest {
    @Test
    void walksASuccessorFromItsRawBytesAndStopsOnAFork() {
        var identity = new EventPacketSamples.Identity("11111111-1111-4111-8111-111111111111", 1,
                "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
                "FC30SN1", "FLIGHT_CONTROLLER", "FC30");
        byte[] first = EventPacketSamples.first(identity, "44444444-4444-4444-8444-444444444444", List.of(position("55555555-5555-4555-8555-555555555555")));
        var parsed = new EventPacketSchema().parse(first, identity.orderId());
        byte[] second = EventPacketSamples.packet(identity, "66666666-6666-4666-8666-666666666666",
                parsed.packetId(), 2, parsed.chainHash(), List.of(position("77777777-7777-4777-8777-777777777777")));
        var secondParsed = new EventPacketSchema().parse(second, identity.orderId());
        var linked = EventChainRepair.walk(0, parsed.anchor(), null, List.of(
                new EventChainRepair.Successor(identity.chainId(), 1, parsed.packetId(), first, parsed.chainHash()),
                new EventChainRepair.Successor(identity.chainId(), 2, secondParsed.packetId(), second, secondParsed.chainHash())));
        assertThat(linked.contiguousHeight()).isEqualTo(2);
        assertThat(linked.integrity()).isEqualTo("OPEN");
        byte[] fork = EventPacketSamples.packet(identity, "99999999-9999-4999-8999-999999999999",
                "88888888-8888-4888-8888-888888888888", 2, new byte[32], List.of(position("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")));
        var forked = new EventPacketSchema().parse(fork, identity.orderId());
        var stopped = EventChainRepair.walk(1, parsed.chainHash(), parsed.packetId(), List.of(
                new EventChainRepair.Successor(identity.chainId(), 2, forked.packetId(), fork, forked.chainHash())));
        assertThat(stopped.contiguousHeight()).isEqualTo(1);
        assertThat(stopped.integrity()).isEqualTo("FORKED");
    }

    private static EventPacketSamples.Ev position(String eventId) {
        return new EventPacketSamples.Ev(eventId, 1_710_000_000_000L, "POSITION", EventPacketSamples.position(1, 2));
    }
}

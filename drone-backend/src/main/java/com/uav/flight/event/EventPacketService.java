package com.uav.flight.event;

import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.pojo.vo.EventPacketAckVO;
import com.uav.flight.pojo.vo.EventProgressVO;
import com.uav.flight.pojo.vo.EventReceiptVO;
import com.uav.flight.pojo.vo.EventResultVO;
import com.uav.flight.pojo.vo.MissingRangeVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Accepts a raw event packet. The receipt is fixed in the same commit as the raw bytes and per-event results. */
@Service
public class EventPacketService {
    private static final Logger log = LoggerFactory.getLogger(EventPacketService.class);
    private final EventChainLock locks;
    private final EventPacketStore store;
    private final EventCommitGate gate;
    private final Clock clock;
    private final EventPacketSchema schema = new EventPacketSchema();

    @Autowired
    public EventPacketService(EventChainLock locks, EventPacketStore store, EventCommitGate gate) {
        this(locks, store, gate, Clock.systemUTC());
    }

    EventPacketService(EventChainLock locks, EventPacketStore store, EventCommitGate gate, Clock clock) {
        this.locks = locks;
        this.store = store;
        this.gate = gate;
        this.clock = clock;
    }

    @Transactional
    public UploadOutcome upload(long pilot, String orderId, byte[] raw) {
        ParsedPacket parsed;
        try {
            parsed = schema.parse(raw, orderId);
        } catch (EventFormatException e) {
            throw mapped(e);
        }
        DispatchOrder order = locks.lock(orderId);
        var existing = store.findPacket(parsed.packetId());
        if (existing != null) return existing(pilot, orderId, raw, parsed, existing);
        authorizeNew(order, pilot, parsed.assignmentVersion());
        var chain = store.findChain(parsed.chainId());
        var binding = store.findBinding(parsed);
        if (chain == null && binding != null) return conflict("CHAIN_BINDING", orderId, binding.chainId(), parsed, raw);
        if (chain != null && chain.pilotId() != pilot) throw denied();
        if (chain != null && !chain.sameIdentity(parsed)) return conflict("CHAIN_IDENTITY", orderId, chain.chainId(), parsed, raw);
        if (chain != null && store.countPackets(chain.chainId()) >= EventLimits.MAX_PACKETS_PER_CHAIN) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.EVENT_PACKET_MALFORMED, "LIMIT");
        }
        if (chain != null && store.packetAtHeight(chain.chainId(), parsed.height()) != null) {
            return conflict("HEIGHT_FORK", orderId, chain.chainId(), parsed, raw);
        }
        boolean created = false;
        if (chain == null) {
            try {
                store.insertChain(pilot, parsed, clock.millis());
                created = true;
            } catch (DataIntegrityViolationException race) {
                // Two commits of the same first packet serialize on the chain primary key.
                // The loser continues and returns the winner's receipt; a different chain id is evidence.
                chain = store.findChain(parsed.chainId());
                if (chain == null) {
                    var bound = store.findBinding(parsed);
                    if (bound != null) return conflict("CHAIN_BINDING", orderId, bound.chainId(), parsed, raw);
                    throw race;
                }
                if (chain.pilotId() != pilot) throw denied();
                if (!chain.sameIdentity(parsed)) return conflict("CHAIN_IDENTITY", orderId, chain.chainId(), parsed, raw);
            }
        }
        try {
            store.insertPacket(parsed, raw, clock.millis());
        } catch (DataIntegrityViolationException e) {
            if (created) store.deleteChainIfEmpty(parsed.chainId());
            var winner = store.findPacket(parsed.packetId());
            if (winner != null && winner.orderId().equals(orderId) && MessageDigest.isEqual(winner.contentDigest(), parsed.contentDigest())) {
                return existing(pilot, orderId, raw, parsed, winner);
            }
            if (winner != null) return conflict("PACKET_CONTENT", orderId, parsed.chainId(), parsed, raw);
            if (store.packetAtHeight(parsed.chainId(), parsed.height()) != null) {
                return conflict("HEIGHT_FORK", orderId, parsed.chainId(), parsed, raw);
            }
            throw e;
        }
        long decidedAt = store.findPacket(parsed.packetId()).decidedAt();
        var decisions = decide(parsed);
        for (int i = 0; i < parsed.events().size(); i++) {
            if (!"ACCEPTED".equals(decisions.get(i).outcome())) continue;
            try {
                store.insertFact(parsed, parsed.events().get(i));
            } catch (DataIntegrityViolationException e) {
                decisions.set(i, revise(parsed, parsed.events().get(i)));
            }
        }
        for (int i = 0; i < parsed.events().size(); i++) store.insertResult(parsed.packetId(), parsed.events().get(i), decisions.get(i));
        store.insertReceipt(parsed, decidedAt);
        var updated = repair(parsed, chain);
        gate.beforeCommit();
        log.info("event packet stored order={} packet={} height={} validation={}",
                orderId, parsed.packetId(), parsed.height(), updated.validationVersion());
        return new UploadOutcome.Stored(ack(parsed.packetId(), updated));
    }

    private UploadOutcome existing(long pilot, String orderId, byte[] raw, ParsedPacket parsed, EventPacketStore.PacketRow row) {
        var chain = store.findChain(row.chainId());
        if (chain == null || chain.pilotId() != pilot) throw denied();
        if (!row.orderId().equals(orderId) || !MessageDigest.isEqual(row.contentDigest(), parsed.contentDigest())) {
            return conflict("PACKET_CONTENT", orderId, row.chainId(), parsed, raw);
        }
        return new UploadOutcome.Stored(ack(row.packetId(), chain));
    }

    private EventDecider.Decision revise(ParsedPacket packet, ParsedEvent event) {
        var fact = store.findFact(event.eventId());
        var attribution = attribution(packet, event);
        if (fact != null && fact.attribution().same(attribution)) {
            return new EventDecider.Decision("DUPLICATE", "DUPLICATE", fact.packetId());
        }
        return new EventDecider.Decision("REJECTED", "EVENT_ID_CONFLICT", null);
    }

    private List<EventDecider.Decision> decide(ParsedPacket packet) {
        long now = clock.millis();
        var decisions = new ArrayList<EventDecider.Decision>();
        var priors = new ArrayList<EventDecider.Prior>();
        for (ParsedEvent event : packet.events()) {
            var fact = store.findFact(event.eventId());
            var existing = fact == null ? null : new EventDecider.Existing(fact.packetId(), fact.attribution());
            boolean interval = EventPayloads.SENSOR.contains(event.type())
                    && store.intervalHit(packet.chainId(), event.type(), event.capturedAt(), event.eventId());
            var decision = EventDecider.decide(event, attribution(packet, event), now, priors, existing, interval);
            decisions.add(decision);
            if ("ACCEPTED".equals(decision.outcome())) priors.add(new EventDecider.Prior(event.type(), event.capturedAt()));
        }
        return decisions;
    }

    private EventPacketStore.ChainRow repair(ParsedPacket parsed, EventPacketStore.ChainRow previous) {
        long contiguous = previous == null ? 0 : previous.contiguousHeight();
        byte[] tipHash = contiguous == 0 ? parsed.anchor() : previous.contiguousHash();
        String tipId = contiguous == 0 ? null : store.packetIdAt(parsed.chainId(), contiguous);
        var tip = EventChainRepair.walk(contiguous, tipHash, tipId, store.successors(parsed.chainId(), contiguous));
        var heights = store.heights(parsed.chainId());
        long highest = heights.isEmpty() ? 0 : heights.getLast();
        long version = (previous == null ? 0 : previous.validationVersion()) + 1;
        store.updateProgress(parsed.chainId(), tip.contiguousHeight(), tip.contiguousHash(), highest, tip.integrity(), version);
        return store.findChain(parsed.chainId());
    }

    private UploadOutcome conflict(String kind, String orderId, String chainId, ParsedPacket parsed, byte[] raw) {
        store.insertConflict(kind, orderId, chainId, parsed.packetId(), raw, kind);
        log.warn("event packet conflict kind={} order={} packet={}", kind, orderId, parsed.packetId());
        return new UploadOutcome.Rejected(HttpStatus.CONFLICT, ApiErrorCode.EVENT_PACKET_CONFLICT, kind);
    }

    private EventPacketAckVO ack(String packetId, EventPacketStore.ChainRow chain) {
        var receipt = store.receipt(packetId);
        var results = store.results(packetId).stream()
                .map(row -> new EventResultVO(row.eventId(), row.outcome(), row.reason(), row.sourcePacketId())).toList();
        var page = EventHeights.page(EventHeights.holes(store.heights(chain.chainId())), null, EventLimits.UPLOAD_MISSING_LIMIT);
        var progress = new EventProgressVO(chain.validationVersion(), chain.contiguousHeight(),
                chain.contiguousHash() == null ? null : EventHex.encode(chain.contiguousHash()), chain.highestReceivedHeight(),
                page.ranges().stream().map(range -> new MissingRangeVO(range.fromHeight(), range.toHeight())).toList(),
                page.cursor(), chain.integrity(), chain.projectionRevision());
        return new EventPacketAckVO(EventLimits.SCHEMA_VERSION, new EventReceiptVO(receipt.receiptVersion(), packetId,
                EventHex.encode(receipt.contentDigest()), EventHex.encode(receipt.chainHash()), receipt.durability(),
                receipt.decidedAt(), results), progress);
    }

    private static EventAttribution attribution(ParsedPacket packet, ParsedEvent event) {
        return new EventAttribution(packet.orderId(), packet.assignmentVersion(), packet.recordingSourceId(), packet.chainId(),
                packet.serialNumber(), packet.serialSource(), packet.modelCode(), event.type(), event.capturedAt(),
                event.payloadCanonical());
    }

    private static void authorizeNew(DispatchOrder order, long pilot, long assignmentVersion) {
        if ("CANCELLED".equals(order.getStage())) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.ORDER_STATUS_INVALID);
        }
        if (!Objects.equals(order.getAssignedPilotId(), pilot) || assignmentVersion != order.getAssignmentVersion()) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.DISPATCH_ASSIGNMENT_CHANGED);
        }
    }

    private static BusinessException mapped(EventFormatException e) {
        ApiErrorCode code = e.kind() == EventFormatException.Kind.HASH
                ? ApiErrorCode.EVENT_PACKET_HASH : ApiErrorCode.EVENT_PACKET_MALFORMED;
        return new BusinessException(HttpStatus.BAD_REQUEST, code, e.getMessage());
    }

    private static BusinessException denied() {
        return new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
    }
}

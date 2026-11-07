package com.uav.flight.event;

import com.uav.flight.pojo.vo.EventHeightVO;
import com.uav.flight.pojo.vo.EventReconciliationVO;
import com.uav.flight.pojo.vo.MissingRangeVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.ArrayList;

/** Reads a fixed validationVersion. A changed version is an explicit error, not a torn page. */
@Service
public class EventReconciliationService {
    private final EventChainLock locks;
    private final EventPacketStore store;
    private final EventReconciliationSchema schema = new EventReconciliationSchema();

    public EventReconciliationService(EventChainLock locks, EventPacketStore store) {
        this.locks = locks;
        this.store = store;
    }

    @Transactional
    public EventReconciliationVO reconcile(long pilot, String orderId, byte[] raw) {
        EventReconciliationSchema.Request request;
        try {
            request = schema.parse(raw);
        } catch (EventFormatException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.EVENT_PACKET_MALFORMED, e.getMessage());
        }
        locks.lock(orderId);
        var chain = store.findChain(request.chainId());
        if (chain == null || !chain.orderId().equals(orderId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND);
        }
        if (chain.pilotId() != pilot) throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
        if (request.validationVersion() != null && request.validationVersion() != chain.validationVersion()) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.EVENT_RECONCILIATION_VERSION_CHANGED,
                    "RECONCILIATION_VERSION_CHANGED current=" + chain.validationVersion());
        }
        var heights = store.heights(chain.chainId());
        var visible = new ArrayList<EventPacketStore.HeightHash>();
        for (var row : store.hashes(chain.chainId(), request.fromHeight(), request.toHeight())) {
            if (request.cursor() == null || row.height() >= request.cursor()) visible.add(row);
        }
        int end = Math.min(visible.size(), request.limit());
        var entries = new ArrayList<EventHeightVO>();
        for (int i = 0; i < end; i++) {
            var row = visible.get(i);
            entries.add(new EventHeightVO(row.height(), row.packetId(), EventHex.encode(row.chainHash()), EventHex.encode(row.contentDigest())));
        }
        Long next = visible.size() > request.limit() ? visible.get(request.limit()).height() : null;
        var holes = EventHeights.page(EventHeights.holesInside(heights, request.fromHeight(), request.toHeight()),
                request.missingCursor(), request.limit());
        return new EventReconciliationVO(EventLimits.SCHEMA_VERSION, chain.validationVersion(), chain.chainId(),
                request.fromHeight(), request.toHeight(), entries, next,
                holes.ranges().stream().map(range -> new MissingRangeVO(range.fromHeight(), range.toHeight())).toList(),
                holes.cursor(), chain.contiguousHeight(),
                chain.contiguousHash() == null ? null : EventHex.encode(chain.contiguousHash()),
                chain.highestReceivedHeight(), chain.integrity(), chain.projectionRevision(),
                commonPrefix(chain, request.prefix()));
    }

    private Long commonPrefix(EventPacketStore.ChainRow chain, java.util.List<EventReconciliationSchema.Request.Prefix> prefix) {
        if (prefix == null) return null;
        long matched = 0;
        for (var entry : prefix) {
            if (entry.height() != matched + 1 || entry.height() > chain.contiguousHeight()) break;
            var rows = store.hashes(chain.chainId(), entry.height(), entry.height());
            if (rows.isEmpty() || !MessageDigest.isEqual(rows.getFirst().chainHash(), entry.chainHash())) break;
            matched = entry.height();
        }
        return matched;
    }
}

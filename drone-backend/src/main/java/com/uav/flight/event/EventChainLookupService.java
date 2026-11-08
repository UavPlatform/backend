package com.uav.flight.event;

import com.uav.flight.pojo.vo.EventChainRefVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Discovers the chains an order+airframe already owns, so a terminal that lost its local journal can continue the
 * existing chain instead of minting a second one.
 *
 * <p>Read-only and deliberately narrow: it never returns packet bodies, and it only reports chains whose order the
 * caller is assigned to. A chain recorded by another recording source is still listed (integrity and heights are
 * operational facts the platform already holds), but it is flagged so the client does not silently claim another
 * terminal's chain as its own continuation point.
 */
@Service
public class EventChainLookupService {
    private final EventChainLock locks;
    private final EventPacketStore store;

    public EventChainLookupService(EventChainLock locks, EventPacketStore store) {
        this.locks = locks;
        this.store = store;
    }

    @Transactional
    public List<EventChainRefVO> chainsFor(long pilot, String orderId, String serialNumber,
            String serialSource, String modelCode, String recordingSourceId) {
        var order = locks.lock(orderId);
        if (!Objects.equals(order.getAssignedPilotId(), pilot)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
        }
        var rows = store.findChainsByAirframe(orderId, serialNumber, serialSource, modelCode);
        var refs = new ArrayList<EventChainRefVO>(rows.size());
        for (var row : rows) {
            refs.add(new EventChainRefVO(row.chainId(), row.recordingSourceId(),
                    !row.recordingSourceId().equals(recordingSourceId),
                    row.contiguousHeight(),
                    row.contiguousHash() == null ? null : EventHex.encode(row.contiguousHash()),
                    row.highestReceivedHeight(), row.integrity(), row.validationVersion(), row.projectionRevision()));
        }
        return List.copyOf(refs);
    }
}

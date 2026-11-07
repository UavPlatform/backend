package com.uav.flight.pojo.vo;

import java.util.List;

/**
 * One bounded page of a chain. commonPrefixHeight is null when the request prefix was null,
 * and 0 when a prefix was supplied and its first entry did not match.
 */
public record EventReconciliationVO(
        int schemaVersion,
        long validationVersion,
        String chainId,
        long fromHeight,
        long toHeight,
        List<EventHeightVO> entries,
        Long nextCursor,
        List<MissingRangeVO> missingRanges,
        Long missingCursor,
        long contiguousHeight,
        String contiguousHash,
        long highestReceivedHeight,
        String integrity,
        long projectionRevision,
        Long commonPrefixHeight) { }

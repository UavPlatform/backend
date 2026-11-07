package com.uav.flight.pojo.vo;

import java.util.List;

/**
 * Dynamic chain view. It is not part of receipt equality.
 * contiguousHeight does not jump holes. highestReceivedHeight is not the contiguous tip.
 * projectionRevision stays 0 until a later phase recomputes projections.
 */
public record EventProgressVO(
        long validationVersion,
        long contiguousHeight,
        String contiguousHash,
        long highestReceivedHeight,
        List<MissingRangeVO> missingRanges,
        Long missingCursor,
        String integrity,
        long projectionRevision) { }

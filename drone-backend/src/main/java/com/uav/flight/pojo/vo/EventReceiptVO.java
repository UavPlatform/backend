package com.uav.flight.pojo.vo;

import java.util.List;

/** Fixed at the commit that stored the raw packet and every event result. Replay returns this object unchanged. */
public record EventReceiptVO(
        int receiptVersion,
        String packetId,
        String contentDigest,
        String chainHash,
        String durability,
        long decidedAt,
        List<EventResultVO> results) { }

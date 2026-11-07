package com.uav.flight.pojo.vo;

/** schemaVersion describes this response envelope. The receipt inside it is immutable. */
public record EventPacketAckVO(int schemaVersion, EventReceiptVO receipt, EventProgressVO progress) { }

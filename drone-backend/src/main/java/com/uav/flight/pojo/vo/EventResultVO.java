package com.uav.flight.pojo.vo;

/** outcome is ACCEPTED, REJECTED, or DUPLICATE. reason is null only for ACCEPTED. */
public record EventResultVO(String eventId, String outcome, String reason, String sourcePacketId) { }

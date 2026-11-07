package com.uav.flight.event;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production cap is {@link EventLimits#MAX_PACKETS_PER_CHAIN}.
 * The HTTP boundary test lowers it so the same admit rule can run without crossing the 600/minute rate limit.
 */
@Component
public class EventPacketCap {
    private final AtomicInteger max = new AtomicInteger(EventLimits.MAX_PACKETS_PER_CHAIN);

    public int max() { return max.get(); }

    public void setForTest(int value) { max.set(value); }
}

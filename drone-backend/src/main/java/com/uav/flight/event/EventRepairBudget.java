package com.uav.flight.event;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test seam for the unverified-span and the repair page size.
 * Production values stay {@link EventLimits#UNVERIFIED_SPAN} and {@link EventLimits#REPAIR_PAGE}.
 */
@Component
public class EventRepairBudget {
    private final AtomicInteger span = new AtomicInteger(EventLimits.UNVERIFIED_SPAN);
    private final AtomicInteger pageSize = new AtomicInteger(EventLimits.REPAIR_PAGE);

    public int span() { return span.get(); }

    public int pageSize() { return Math.max(1, pageSize.get()); }

    public void setForTest(int span, int pageSize) {
        this.span.set(span);
        this.pageSize.set(Math.max(1, pageSize));
    }

    public void reset() {
        span.set(EventLimits.UNVERIFIED_SPAN);
        pageSize.set(EventLimits.REPAIR_PAGE);
    }
}

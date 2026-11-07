package com.uav.flight.event;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/** Test seam. A failure here is inside the upload transaction, so the raw packet and receipt roll back together. */
@Component
public class EventCommitGate {
    private final AtomicReference<RuntimeException> pending = new AtomicReference<>();

    public void beforeCommit() {
        RuntimeException failure = pending.getAndSet(null);
        if (failure != null) throw failure;
    }

    public void failNextCommit() {
        pending.set(new IllegalStateException("event packet commit failed before durability"));
    }

    public void clear() { pending.set(null); }
}

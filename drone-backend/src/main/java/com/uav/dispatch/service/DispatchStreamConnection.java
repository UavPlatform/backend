package com.uav.dispatch.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded authenticated lease. No order, contact, credential or price data. */
final class DispatchStreamConnection {
    final Long user;
    final int role;
    final SseEmitter emitter;
    final long expiresAt;
    final AtomicBoolean dirty = new AtomicBoolean();
    final AtomicBoolean writing = new AtomicBoolean();
    private boolean ready;
    private long lastWrite;
    private long lastSync;
    DispatchStreamConnection(Long user, int role, SseEmitter emitter, long expiresAt) {
        this.user = user; this.role = role; this.emitter = emitter; this.expiresAt = expiresAt;
    }
    String nextEvent(long now) {
        if (!ready) {
            ready = true; lastSync = now; lastWrite = now; dirty.set(false); return "ready";
        }
        // A periodic resync repairs cross-instance/missed events without a broker.
        if (now - lastSync >= 30_000) {
            lastSync = now; lastWrite = now; dirty.set(false); return "resync";
        }
        if (dirty.getAndSet(false)) { lastWrite = now; return "change"; }
        if (now - lastWrite >= 15_000) { lastWrite = now; return "heartbeat"; }
        return null;
    }
}

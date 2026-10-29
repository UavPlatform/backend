package com.uav.dispatch.service;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** REQ-ALL-XC-004/005: payload-free invalidations, emitted only after commit. */
@Service
public class DispatchEventStreamService {
    private final Supplier<SseEmitter> emitters;
    private final ConcurrentMap<Long, DispatchStreamConnection> connections = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final ThreadPoolExecutor writers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                var thread = new Thread(runnable, "dispatch-sse-writer"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public DispatchEventStreamService() { this(() -> new SseEmitter(65_000L)); }
    DispatchEventStreamService(Supplier<SseEmitter> emitters) { this.emitters = emitters; }

    public synchronized SseEmitter open(Long user, int role, long tokenExpiresAt) {
        long now = System.currentTimeMillis();
        if (tokenExpiresAt <= now) throw new BusinessException(HttpStatus.UNAUTHORIZED, ApiErrorCode.NO_PERMISSION);
        if (connections.size() >= 512 || connections.values().stream().filter(c -> c.user.equals(user)).count() >= 4) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.INVALID_PARAM, "实时连接过多，请稍后重试");
        }
        var emitter = emitters.get();
        long id = ids.incrementAndGet();
        var connection = new DispatchStreamConnection(user, role, emitter, Math.min(tokenExpiresAt, now + 60_000));
        connections.put(id, connection);
        emitter.onCompletion(() -> connections.remove(id, connection));
        // Servlet callbacks already own completion. Do not flush a timed-out or broken response.
        emitter.onTimeout(() -> connections.remove(id, connection));
        emitter.onError(error -> connections.remove(id, connection));
        return emitter;
    }

    public void afterCommit(Long owner, Long pilot, boolean hall) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Dispatch stream changes require a real transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { mark(owner, pilot, hall); }
        });
    }

    private void mark(Long owner, Long pilot, boolean hall) {
        connections.values().forEach(c -> {
            if (c.user.equals(owner) || c.user.equals(pilot) || hall && c.role == 1) c.dirty.set(true);
        });
    }

    /** Network writes never occupy the order transaction thread or scheduler. */
    @Scheduled(fixedDelay = 250)
    public void flush() {
        long now = System.currentTimeMillis();
        connections.forEach((id, c) -> {
            if (now >= c.expiresAt) { close(id, c); return; }
            if (!c.writing.compareAndSet(false, true)) return;
            String event = c.nextEvent(now);
            if (event == null) { c.writing.set(false); return; }
            try {
                writers.execute(() -> {
                    // Serialize normal completion with writes so expiry cannot flush after a failed send.
                    synchronized (c) {
                        try {
                            if (connections.get(id) == c && System.currentTimeMillis() < c.expiresAt) {
                                c.emitter.send(SseEmitter.event().name(event).reconnectTime(1000).data("{}"));
                            }
                        } catch (Exception error) {
                            // Spring dispatches send failures through the container's async lifecycle.
                            connections.remove(id, c);
                        } finally { c.writing.set(false); }
                    }
                });
            } catch (RejectedExecutionException error) { c.writing.set(false); close(id, c); }
        });
    }

    private void close(Long id, DispatchStreamConnection connection) {
        synchronized (connection) {
            if (connections.remove(id, connection)) connection.emitter.complete();
        }
    }
    @PreDestroy public void shutdown() {
        connections.forEach(this::close);
        writers.shutdownNow();
    }
}

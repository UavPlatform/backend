package com.uav.dispatch.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** REQ-ALL-XC-004/005: a disconnected servlet response must not be completed again. */
class DispatchEventStreamLifecycleTest {
    @Test void containerErrorReleasesTheLeaseWithoutCompletingTheBrokenResponse() {
        var emitter = mock(SseEmitter.class);
        var service = new DispatchEventStreamService(() -> emitter);
        try {
            service.open(1L, 0, System.currentTimeMillis() + 60_000);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Consumer<Throwable>> callback = ArgumentCaptor.forClass(Consumer.class);
            verify(emitter).onError(callback.capture());
            callback.getValue().accept(new AsyncRequestNotUsableException("disconnected"));
            // Removal must also prevent shutdown from touching the failed response.
            service.shutdown();
            verify(emitter, never()).complete();
            verify(emitter, never()).completeWithError(any());
        } finally { service.shutdown(); }
    }

    @Test void containerTimeoutDoesNotFlushTheResponseAgain() {
        var emitter = mock(SseEmitter.class);
        var service = new DispatchEventStreamService(() -> emitter);
        try {
            service.open(1L, 0, System.currentTimeMillis() + 60_000);
            var callback = ArgumentCaptor.forClass(Runnable.class);
            verify(emitter).onTimeout(callback.capture());
            callback.getValue().run();
            service.shutdown();
            verify(emitter, never()).complete();
        } finally { service.shutdown(); }
    }

    @Test void failedNetworkWriteDoesNotTriggerAnotherFlush() throws Exception {
        var emitter = mock(SseEmitter.class);
        doThrow(new AsyncRequestNotUsableException("broken pipe"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var service = new DispatchEventStreamService(() -> emitter);
        try {
            service.open(1L, 0, System.currentTimeMillis() + 60_000);
            service.flush();
            var writers = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "writers");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
                assertThat(writers.getActiveCount()).isZero();
            });
            service.shutdown();
            verify(emitter, never()).complete();
            verify(emitter, never()).completeWithError(any());
        } finally { service.shutdown(); }
    }

    @Test void healthyShutdownStillCompletesEachLeaseExactlyOnce() {
        var emitter = mock(SseEmitter.class);
        var service = new DispatchEventStreamService(() -> emitter);
        service.open(1L, 0, System.currentTimeMillis() + 60_000);
        service.shutdown();
        service.shutdown();
        verify(emitter).complete();
    }
}

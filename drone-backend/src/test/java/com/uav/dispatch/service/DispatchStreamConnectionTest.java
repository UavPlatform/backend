package com.uav.dispatch.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;

class DispatchStreamConnectionTest {
    @Test void heartbeatDoesNotPretendDataChangedAndPeriodicResyncRepairsMissedSignals() {
        var connection = new DispatchStreamConnection(1L, 0, new SseEmitter(), 100_000);
        assertThat(connection.nextEvent(1000)).isEqualTo("ready");
        assertThat(connection.nextEvent(1500)).isNull();
        assertThat(connection.nextEvent(16_000)).isEqualTo("heartbeat");
        connection.dirty.set(true);
        assertThat(connection.nextEvent(17_000)).isEqualTo("change");
        assertThat(connection.nextEvent(17_001)).isNull();
        assertThat(connection.nextEvent(31_000)).isEqualTo("resync");
    }
    @Test void noTransactionCannotPublishAndExpiredLeaseCannotOpen() {
        var service = new DispatchEventStreamService();
        try {
            assertThatThrownBy(() -> service.afterCommit(1L, null, false)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.open(1L, 0, System.currentTimeMillis() - 1)).isInstanceOf(RuntimeException.class);
        } finally { service.shutdown(); }
    }
}

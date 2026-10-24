package com.uav.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.flight.pojo.entity.DispatchLiveSession;
import com.uav.flight.service.DispatchLiveSigner;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.InflaterInputStream;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.*;

/** REQ-SHIP-EXEC-004: inspect Tencent ticket bytes to verify viewer cannot publish or change rooms. */
class DispatchLiveSignerTest {
    @Test void existingApplicationIsAcceptedOnlyWithConfiguredRoomPermissions() {
        assertThat(new DispatchLiveSigner(0, "", false).available()).isFalse();
        assertThat(new DispatchLiveSigner(101, "dummy-test-key", false).available()).isFalse();
        assertThat(new DispatchLiveSigner(100, "dummy-test-key", true).available()).isTrue();
    }
    @Test void viewerTicketIsReceiveOnlyAndBothTicketsAreBoundToSessionRoom() throws Exception {
        var session = new DispatchLiveSession(); session.setId("12345678-1234-1234-1234-123456789abc"); session.setConnectionId("connection");
        var signer = new DispatchLiveSigner(101, "dummy-test-key", true);
        var publisher = signer.issue(session, true); var viewer = signer.issue(session, false);
        assertThat(viewer.userId()).isNotEqualTo(publisher.userId());
        assertTicket(publisher.privateMapKey(), publisher.roomId(), publisher.userId(), 19);
        assertTicket(viewer.privateMapKey(), viewer.roomId(), viewer.userId(), 34);
    }
    private void assertTicket(String ticket, String room, String user, int rights) throws Exception {
        byte[] compressed = Base64.getDecoder().decode(ticket.replace('*', '+').replace('-', '/').replace('_', '='));
        var json = new ObjectMapper().readTree(new InflaterInputStream(new ByteArrayInputStream(compressed)).readAllBytes());
        assertThat(json.path("TLS.identifier").asText()).isEqualTo(user);
        var buf = ByteBuffer.wrap(Base64.getDecoder().decode(json.path("TLS.userbuf").asText()));
        assertThat(buf.get()).isEqualTo((byte) 1);
        int length = Short.toUnsignedInt(buf.getShort()); byte[] name = new byte[length]; buf.get(name);
        assertThat(new String(name, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(user);
        assertThat(buf.getInt()).isEqualTo(101); assertThat(buf.getInt()).isZero();
        buf.getInt(); // expiry timestamp
        assertThat(buf.getInt()).isEqualTo(rights); assertThat(buf.getInt()).isZero();
        int roomLength = Short.toUnsignedInt(buf.getShort()); byte[] roomBytes = new byte[roomLength]; buf.get(roomBytes);
        assertThat(new String(roomBytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(room);
    }
}

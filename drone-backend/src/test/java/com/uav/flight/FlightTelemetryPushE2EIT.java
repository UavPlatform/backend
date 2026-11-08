package com.uav.flight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.DispatchTestData;
import com.uav.flight.event.EventPacketSamples;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-SUP-MON-005/007, ADR-0014: an accepted event packet reaches a watching web client over {@code /ws/web}.
 *
 * <p>Real TCP and a real WebSocket, so the test covers the whole wiring: upload transaction → committed
 * projection → after-commit push. It fails if the push is only wired to the retired v1 sample path.
 */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class FlightTelemetryPushE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("上传事件包后，监管 WS 收到带独立采集时间的 FLIGHT_TELEMETRY")
    void acceptedPacketIsPushedToTheOrderWatcher() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("push"), null);
        String orderId = prepare(owner, pilot);
        long version = version(orderId);
        var identity = new EventPacketSamples.Identity(orderId, version, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "1581F5BKD225400P0001", "FLIGHT_CONTROLLER", "FC30");
        String eventId = UUID.randomUUID().toString();
        long capturedAt = System.currentTimeMillis();
        byte[] packet = EventPacketSamples.first(identity, UUID.randomUUID().toString(), List.of(
                new EventPacketSamples.Ev(eventId, capturedAt, "POSITION", EventPacketSamples.position(282282000, 1129388000))));

        var admin = accounts().adminLogin();
        var inbox = new TelemetryInbox();
        Session session = ContainerProvider.getWebSocketContainer().connectToServer(inbox,
                ClientEndpointConfig.Builder.create().build(),
                URI.create(wsBaseUrl() + "/ws/web?orderId=" + orderId + "&token=" + admin.token()));
        try {
            assertThat(inbox.opened.get(10, TimeUnit.SECONDS)).isTrue();
            send(pilot.token(), "/dispatch/pilot/orders/" + orderId + "/event-packets", packet);

            String message = inbox.message.get(10, TimeUnit.SECONDS);
            assertThat(message).as("no FLIGHT_TELEMETRY was pushed").isNotNull();
            JsonNode envelope = json.readTree(message);
            assertThat(envelope.path("type").asText()).isEqualTo("event");
            assertThat(envelope.path("name").asText()).isEqualTo("FLIGHT_TELEMETRY");
            assertThat(envelope.path("deviceId").asText()).isEqualTo("1581F5BKD225400P0001");
            assertThat(envelope.path("timestamp").asLong()).isEqualTo(capturedAt);
            JsonNode data = envelope.path("data");
            assertThat(data.path("orderId").asText()).isEqualTo(orderId);
            assertThat(data.path("eventId").asText()).isEqualTo(eventId);
            assertThat(data.path("type").asText()).isEqualTo("POSITION");
            assertThat(data.path("capturedAt").asLong()).isEqualTo(capturedAt);
            assertThat(data.path("serialSource").asText()).isEqualTo("FLIGHT_CONTROLLER");
            assertThat(data.path("payload").path("latitudeDegE7").asLong()).isEqualTo(282282000L);
            // ADR-0014: no synthetic combined sample; the raw event fields are what the client sees.
            assertThat(data.has("altitude")).isFalse();
        } finally {
            session.close();
        }
    }

    /** @return the order id after a real claim, so the pilot owns the assignment version. */
    private String prepare(TestAccounts.Account owner, TestAccounts.Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        String assessment = send(owner.token(), "/dispatch/assessments", waybill).path("assessmentId").asText();
        String id = send(owner.token(), "/dispatch/orders", Map.of("assessmentId", assessment,
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill))
                .path("orderId").asText();
        send(owner.token(), "/dispatch/orders/" + id + "/simulate-payment", Map.of());
        JsonNode hall = get(pilot.token(), "/dispatch/pilot/hall");
        JsonNode offer = null;
        for (JsonNode item : hall.path("items")) if (id.equals(item.path("orderId").asText())) offer = item;
        assertThat(offer).as("claimed order must appear in the pilot hall").isNotNull();
        send(pilot.token(), "/dispatch/pilot/orders/" + id + "/claim",
                Map.of("offerRevision", offer.path("offerRevision").asLong()));
        return id;
    }

    private long version(String orderId) throws Exception {
        var admin = accounts().adminLogin();
        JsonNode detail = get(admin.token(), "/admin/dispatch/orders/" + orderId).path("order");
        return detail.path("assignmentVersion").asLong();
    }

    private JsonNode send(String token, String path, Object body) throws Exception {
        return send(token, path, json.writeValueAsBytes(body));
    }

    private JsonNode send(String token, String path, byte[] body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("POST %s: %s", path, response.body()).isEqualTo(200);
            return json.readTree(response.body()).path("data");
        }
    }

    private JsonNode get(String token, String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Authorization", "Bearer " + token).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
            return json.readTree(response.body()).path("data");
        }
    }

    /** Collects the first pushed envelope; the assertions above decide whether it is the expected one. */
    private static final class TelemetryInbox extends Endpoint implements MessageHandler.Whole<String> {
        private final CompletableFuture<Boolean> opened = new CompletableFuture<>();
        private final CompletableFuture<String> message = new CompletableFuture<>();

        @Override public void onOpen(Session session, EndpointConfig config) {
            session.addMessageHandler(this);
            opened.complete(true);
        }

        @Override public void onMessage(String text) { message.complete(text); }

        @Override public void onError(Session session, Throwable error) {
            opened.completeExceptionally(error);
            message.completeExceptionally(error);
        }
    }
}

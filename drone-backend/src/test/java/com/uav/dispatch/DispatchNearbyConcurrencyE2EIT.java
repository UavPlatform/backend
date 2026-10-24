package com.uav.dispatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.service.DispatchNearbyService;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true", "dispatch.nearby.scheduler-enabled=false"})
class DispatchNearbyConcurrencyE2EIT extends RealProtocolTestBase {
    @Autowired DispatchNearbyService nearby;
    private final ObjectMapper json = new ObjectMapper();
    private HttpRequest request(Account actor, String path, String method, Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create(baseUrl() + path)).header("Authorization", "Bearer " + actor.token())
                .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    private JsonNode send(HttpClient client, Account actor, String path, String method, Object body) throws Exception {
        var response = client.send(request(actor, path, method, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200); return json.readTree(response.body()).path("data");
    }
    @Test void concurrentWorkersDoNotDuplicateAndLateHttpUpdateCannotReviveAStoppedSession() throws Exception {
        var owner = accounts().registerUser(); var pilot = accounts().registerRider(UniqueNames.userName("near-race"), null);
        try (var client = HttpClient.newHttpClient()) {
            String key = send(client, pilot, "/dispatch/pilot/position-session", "POST", Map.of()).asText();
            var fix = Map.of("sessionKey", key, "latitude", 28.2, "longitude", 113.0, "accuracyMeters", 12,
                    "capturedAt", System.currentTimeMillis(), "coordinateSystem", "GCJ02", "mocked", false);
            send(client, pilot, "/dispatch/pilot/position", "PUT", fix);
            var waybill = DispatchTestData.waybill(DispatchTestData.fields());
            String assessment = send(client, owner, "/dispatch/assessments", "POST", waybill).path("assessmentId").asText();
            String id = send(client, owner, "/dispatch/orders", "POST", Map.of("assessmentId", assessment, "requestKey", UUID.randomUUID().toString(),
                    "declarationAccepted", true, "waybill", waybill)).path("orderId").asText();
            send(client, owner, "/dispatch/orders/" + id + "/simulate-payment", "POST", Map.of());
            CompletableFuture.allOf(CompletableFuture.runAsync(() -> nearby.process(id)), CompletableFuture.runAsync(() -> nearby.process(id))).join();
            assertThat(send(client, owner, "/dispatch/orders/" + id + "/nearby-dispatch", "GET", null).path("notifiedPilotCount").asLong()).isEqualTo(1);
            var update = client.sendAsync(request(pilot, "/dispatch/pilot/position", "PUT", fix), HttpResponse.BodyHandlers.ofString());
            var stop = client.sendAsync(request(pilot, "/dispatch/pilot/position?sessionKey=" + key, "DELETE", null), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(update, stop).join();
            assertThat(update.get().statusCode()).isIn(200, 409); assertThat(stop.get().statusCode()).isEqualTo(200);
            assertThat(send(client, pilot, "/dispatch/pilot/nearby-hall", "GET", null).path("locationState").asText()).isEqualTo("LOCATION_REQUIRED");
        }
    }
}

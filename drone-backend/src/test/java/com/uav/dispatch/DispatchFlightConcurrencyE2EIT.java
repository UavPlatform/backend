package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true", "trtc.sdk-app-id=1900000999", "trtc.secret-key=unit-test-only-signing-key", "dispatch.live.room-permissions-enabled=true"})
class DispatchFlightConcurrencyE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private Account pilot() { return accounts().registerRider(UniqueNames.userName("dispatchpilot"), null); }
    private HttpRequest request(Account actor, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).header("Authorization", "Bearer " + actor.token())
                .header("Content-Type", "application/json");
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return request.build();
    }
    private JsonNode send(HttpClient client, Account actor, String path, Object body) throws Exception {
        var response = client.send(request(actor, path, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    private String paid(HttpClient client, Account owner) throws Exception {
        var fields = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = send(client, owner, "/dispatch/assessments", fields);
        String id = send(client, owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", fields)).path("orderId").asText();
        send(client, owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of());
        return id;
    }
    private long revision(HttpClient client, Account pilot, String id) throws Exception {
        for (int page = 0; ; page++) {
            var hall = send(client, pilot, "/dispatch/pilot/hall?page=" + page, null);
            for (var card : hall.path("items")) if (card.path("orderId").asText().equals(id)) return card.path("offerRevision").asLong();
            assertThat(hall.path("hasNext").asBoolean()).as("created paid order should be in hall").isTrue();
        }
    }
    private String arrived(HttpClient client, Account owner, Account pilot) throws Exception {
        String id = paid(client, owner), path = "/dispatch/pilot/orders/" + id;
        send(client, pilot, path + "/claim", Map.of("offerRevision", revision(client, pilot, id)));
        send(client, pilot, path + "/arrive", Map.of("assignmentVersion", 1));
        return id;
    }
    @Test void physicalSerialCanOnlyBeActiveInOneCommittedOrder() throws Exception {
        var owner = accounts().registerUser(); var a = pilot(); var b = pilot();
        try (var client = HttpClient.newHttpClient()) {
            String x = arrived(client, owner, a), y = arrived(client, owner, b), serial = UniqueNames.djiId();
            var body = Map.of("assignmentVersion", 1, "requestKey", UUID.randomUUID().toString(), "serialNumber", serial,
                    "modelCode", "FC30", "modelName", "DJI FlyCart 30");
            String px = "/dispatch/pilot/orders/" + x + "/flight-connections", py = "/dispatch/pilot/orders/" + y + "/flight-connections";
            var first = client.sendAsync(request(a, px, body), HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(request(b, py, body), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(first, second).join();
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
            String winningId = first.get().statusCode() == 200 ? x : y;
            Account winner = first.get().statusCode() == 200 ? a : b;
            String cid = send(client, owner, "/dispatch/orders/" + winningId + "/flight", null).path("connection").path("connectionId").asText();
            String samples = "/dispatch/pilot/orders/" + winningId + "/flight-connections/" + cid + "/samples";
            var point = Map.of("sequence", 0, "capturedAt", System.currentTimeMillis(), "latitude", 28.2, "longitude", 113.0);
            var s1 = client.sendAsync(request(winner, samples, point), HttpResponse.BodyHandlers.ofString());
            var s2 = client.sendAsync(request(winner, samples, point), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(s1, s2).join();
            assertThat(s1.get().statusCode()).isEqualTo(200); assertThat(s2.get().statusCode()).isEqualTo(200);
            assertThat(json.readTree(s1.get().body()).path("data").path("sampleId")).isEqualTo(json.readTree(s2.get().body()).path("data").path("sampleId"));
            assertThat(send(client, owner, "/dispatch/orders/" + winningId + "/flight/track", null).path("items").size()).isEqualTo(1);
            String orderBase = "/dispatch/orders/" + winningId;
            send(client, winner, orderBase + "/start-transport", Map.of("assignmentVersion", 1));
            String liveBase = "/dispatch/pilot/orders/" + winningId + "/flight-connections/" + cid + "/live";
            var liveRequest = Map.of("requestKey", UUID.randomUUID().toString());
            var l1 = client.sendAsync(request(winner, liveBase, liveRequest), HttpResponse.BodyHandlers.ofString());
            var l2 = client.sendAsync(request(winner, liveBase, liveRequest), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(l1, l2).join();
            assertThat(l1.get().statusCode()).isEqualTo(200); assertThat(l2.get().statusCode()).isEqualTo(200);
            String liveId = json.readTree(l1.get().body()).path("data").path("liveId").asText();
            assertThat(json.readTree(l2.get().body()).path("data").path("liveId").asText()).isEqualTo(liveId);
            send(client, winner, liveBase + "/" + liveId + "/heartbeat", Map.of("state", "RUNNING"));
            var watch = client.sendAsync(request(owner, orderBase + "/live/" + liveId + "/watch", Map.of()), HttpResponse.BodyHandlers.ofString());
            var cancel = client.sendAsync(request(owner, orderBase + "/cancel", Map.of()), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(watch, cancel).join();
            assertThat(watch.get().statusCode()).isIn(200, 409); assertThat(cancel.get().statusCode()).isEqualTo(200);
            assertThat(send(client, owner, orderBase + "/live", null).path("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(client.send(request(owner, orderBase + "/live/" + liveId + "/watch", Map.of()), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);

        }
    }
}

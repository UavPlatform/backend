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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

/** REQ-SHIP-EXEC-002 / REQ-PILOT-EXEC-006: committed HTTP requests race on the order lock. */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchChatConcurrencyE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private HttpRequest request(Account actor, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + actor.token()).header("Content-Type", "application/json");
        if (body != null) builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return builder.build();
    }
    private JsonNode send(HttpClient client, Account actor, String path, Object body) throws Exception {
        var response = client.send(request(actor, path, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    @Test void concurrentOpeningReusesOneSessionAndReleaseSerializesWithSend() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("racechat"), null);
        try (var client = HttpClient.newHttpClient()) {
            var fields = DispatchTestData.waybill(DispatchTestData.fields());
            var assessment = send(client, owner, "/dispatch/assessments", fields);
            String id = send(client, owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                    "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", fields)).path("orderId").asText();
            String ownerPath = "/dispatch/orders/" + id, pilotPath = "/dispatch/pilot/orders/" + id;
            send(client, owner, ownerPath + "/simulate-payment", Map.of());
            long revision = -1;
            for (int page = 0; revision < 0; page++) {
                var hall = send(client, pilot, "/dispatch/pilot/hall?page=" + page, null);
                for (var card : hall.path("items")) if (id.equals(card.path("orderId").asText())) revision = card.path("offerRevision").asLong();
                if (revision < 0) assertThat(hall.path("hasNext").asBoolean()).isTrue();
            }
            send(client, pilot, pilotPath + "/claim", Map.of("offerRevision", revision));
            var a = client.sendAsync(request(owner, ownerPath + "/chat", Map.of("assignmentVersion", 1)), HttpResponse.BodyHandlers.ofString());
            var b = client.sendAsync(request(pilot, ownerPath + "/chat", Map.of("assignmentVersion", 1)), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(a, b).join();
            assertThat(a.get().statusCode()).isEqualTo(200); assertThat(b.get().statusCode()).isEqualTo(200);
            long session = json.readTree(a.get().body()).path("data").path("sessionId").asLong();
            assertThat(json.readTree(b.get().body()).path("data").path("sessionId").asLong()).isEqualTo(session);
            var sent = client.sendAsync(request(owner, "/chat/Message/send", Map.of("sessionId", session, "content", "并发现场沟通")), HttpResponse.BodyHandlers.ofString());
            var released = client.sendAsync(request(pilot, pilotPath + "/release", Map.of("assignmentVersion", 1)), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(sent, released).join();
            assertThat(sent.get().statusCode()).isIn(200, 403); assertThat(released.get().statusCode()).isEqualTo(200);
            for (var actor : java.util.List.of(owner, pilot)) {
                assertThat(client.send(request(actor, "/chat/Message/messages/" + session, null), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
                assertThat(send(client, actor, "/chat/session/list", null).size()).isZero();
            }
        }
    }
}

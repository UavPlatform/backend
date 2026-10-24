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

@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchMatchingConcurrencyE2EIT extends RealProtocolTestBase {
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
    @Test void twoPilotsCompeteOnlyOneWinsAndStaleReleaseCannotDislodgeReplacement() throws Exception {
        var owner = accounts().registerUser(); var a = pilot(); var b = pilot();
        try (var client = HttpClient.newHttpClient()) {
            String id = paid(client, owner); String path = "/dispatch/pilot/orders/" + id;
            var body = Map.of("offerRevision", revision(client, a, id));
            var first = client.sendAsync(request(a, path + "/claim", body), HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(request(b, path + "/claim", body), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(first, second).join();
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
            Account winner = first.get().statusCode() == 200 ? a : b;
            Account loser = winner == a ? b : a;
            long epoch = send(client, winner, path, null).path("assignmentVersion").asLong();
            assertThat(send(client, owner, "/dispatch/orders/" + id, null).path("assignedPilotId").asLong()).isEqualTo(winner.id());
            assertThat(send(client, winner, path + "/release", Map.of("assignmentVersion", epoch)).isNull()).isTrue();
            var replacement = send(client, loser, path + "/claim", Map.of("offerRevision", revision(client, loser, id)));
            send(client, winner, path + "/release", Map.of("assignmentVersion", epoch));
            var actual = send(client, loser, path, null);
            assertThat(actual.path("assignedPilotId").asLong()).isEqualTo(loser.id());
            assertThat(actual.path("assignmentVersion").asLong()).isEqualTo(replacement.path("assignmentVersion").asLong());
        }
    }
    @Test void claimAndShipperCancellationRaceCannotLeaveActivePaidAssignment() throws Exception {
        var owner = accounts().registerUser(); var p = pilot();
        try (var client = HttpClient.newHttpClient()) {
            String id = paid(client, owner);
            var claim = client.sendAsync(request(p, "/dispatch/pilot/orders/" + id + "/claim",
                    Map.of("offerRevision", revision(client, p, id))), HttpResponse.BodyHandlers.ofString());
            var cancel = client.sendAsync(request(owner, "/dispatch/orders/" + id + "/cancel", Map.of()), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(claim, cancel).join();
            assertThat(claim.get().statusCode()).isIn(200, 409);
            assertThat(cancel.get().statusCode()).isEqualTo(200);
            var actual = send(client, owner, "/dispatch/orders/" + id, null);
            assertThat(actual.path("stage").asText()).isEqualTo("CANCELLED");
            assertThat(actual.path("fundsStatus").asText()).isEqualTo("REFUNDED");
            if (claim.get().statusCode() == 200) assertThat(send(client, p, "/dispatch/pilot/orders/" + id, null)
                    .path("stage").asText()).isEqualTo("CANCELLED");
        }
    }
}

package com.uav.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.UniqueNames;
import com.uav.user.mapper.RiderUavRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Concurrent approve of the same last in-stock airframe: only one loan wins. */
class EquipmentConcurrencyE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired RiderUavRepository bindings;
    private HttpRequest request(String token, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else request.GET();
        return request.build();
    }
    private JsonNode send(HttpClient client, String token, String path, Object body) throws Exception {
        var response = client.send(request(token, path, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP %s", path).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    @Test void onePhysicalDroneCanOnlyBeApprovedOnceAndOldReturnCannotEndNewLoan() throws Exception {
        var admin = accounts().adminLogin();
        var a = accounts().registerRider(UniqueNames.userName("loana"), null);
        var b = accounts().registerRider(UniqueNames.userName("loanb"), null);
        Long modelId = fixtures.defaultAircraftModelId();
        try (var client = HttpClient.newHttpClient()) {
            String token = admin.token();
            String id = send(client, token, "/admin/drones", Map.of("aircraftModelId", modelId,
                    "serialNumber", UniqueNames.djiId())).path("droneId").asText();
            String appA = send(client, a.token(), "/equipment/pilot/borrows",
                    Map.of("aircraftModelId", modelId, "requestKey", UUID.randomUUID().toString())).path("borrowId").asText();
            String appB = send(client, b.token(), "/equipment/pilot/borrows",
                    Map.of("aircraftModelId", modelId, "requestKey", UUID.randomUUID().toString())).path("borrowId").asText();
            var approveBody = Map.of("droneId", id);
            var first = client.sendAsync(request(token, "/admin/borrow-applications/" + appA + "/approve", approveBody), HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(request(token, "/admin/borrow-applications/" + appB + "/approve", approveBody), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
            boolean aWon = first.get().statusCode() == 200;
            String winnerApp = aWon ? appA : appB;
            var winner = aWon ? a : b;
            var loser = aWon ? b : a;
            String loserApp = aWon ? appB : appA;

            send(client, winner.token(), "/equipment/pilot/borrows/" + winnerApp + "/return", Map.of());
            // Reject loser pending (no stock) then loser applies again after return and gets approved.
            client.send(request(token, "/admin/borrow-applications/" + loserApp + "/reject", Map.of("reason", "已借出")),
                    HttpResponse.BodyHandlers.ofString());
            String current = send(client, loser.token(), "/equipment/pilot/borrows",
                    Map.of("aircraftModelId", modelId, "requestKey", UUID.randomUUID().toString())).path("borrowId").asText();
            send(client, token, "/admin/borrow-applications/" + current + "/approve", Map.of("droneId", id));
            // Old return retry must not clear the new loan.
            send(client, winner.token(), "/equipment/pilot/borrows/" + winnerApp + "/return", Map.of());
            var actual = send(client, loser.token(), "/equipment/pilot/borrows/current", null).path("items").get(0);
            assertThat(actual.path("borrowId").asText()).isEqualTo(current);
            assertThat(actual.path("returnedAt").isNull()).isTrue();
            assertThat(bindings.findByUserId(winner.id())).isEmpty();
            assertThat(bindings.findByUserId(loser.id())).isEmpty();
            assertThat(send(client, token, "/admin/drones/" + id + "/borrows", null).path("totalElements").asLong()).isEqualTo(2);
        }
    }
}

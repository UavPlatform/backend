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

class EquipmentConcurrencyE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired RiderUavRepository bindings;
    private HttpRequest request(String token, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return request.build();
    }
    private JsonNode send(HttpClient client, String token, String path, Object body) throws Exception {
        var response = client.send(request(token, path, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP %s", path).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    private JsonNode provider(HttpClient client, String admin) throws Exception {
        String name = UniqueNames.userName("equipment");
        send(client, admin, "/equipment/admin/providers", Map.of("username", name, "password", "equipment-test-pass", "displayName", "并发测试机场"));
        return send(client, null, "/equipment/provider/login", Map.of("username", name, "password", "equipment-test-pass"));
    }
    @Test void onePhysicalDroneCanOnlyBeBorrowedOnceAndOldReturnCannotEndNewLoan() throws Exception {
        var admin = accounts().adminLogin();
        var a = accounts().registerRider(UniqueNames.userName("loana"), null); var b = accounts().registerRider(UniqueNames.userName("loanb"), null);
        try (var client = HttpClient.newHttpClient()) {
            var provider = provider(client, admin.token()); String token = provider.path("token").asText();
            String id = send(client, token, "/equipment/provider/drones", Map.of("aircraftModelId", fixtures.defaultAircraftModelId(),
                    "serialNumber", UniqueNames.djiId(), "airportName", "测试机场", "latitude", 28.2, "longitude", 113.0)).path("droneId").asText();
            var inputA = Map.of("droneId", id, "requestKey", UUID.randomUUID().toString());
            var inputB = Map.of("droneId", id, "requestKey", UUID.randomUUID().toString());
            var first = client.sendAsync(request(a.token(), "/equipment/pilot/borrows", inputA), HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(request(b.token(), "/equipment/pilot/borrows", inputB), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
            var winner = first.get().statusCode() == 200 ? a : b; var loser = winner == a ? b : a;
            var input = winner == a ? inputA : inputB;
            var loan = send(client, winner.token(), "/equipment/pilot/borrows", input);
            String old = loan.path("borrowId").asText();
            send(client, winner.token(), "/equipment/pilot/borrows/" + old + "/return", Map.of());
            String current = send(client, loser.token(), "/equipment/pilot/borrows", Map.of("droneId", id, "requestKey", UUID.randomUUID().toString())).path("borrowId").asText();
            send(client, winner.token(), "/equipment/pilot/borrows/" + old + "/return", Map.of());
            var actual = send(client, loser.token(), "/equipment/pilot/borrows", null).path("items").get(0);
            assertThat(actual.path("borrowId").asText()).isEqualTo(current);
            assertThat(actual.path("returnedAt").isNull()).isTrue();
            assertThat(bindings.findByUserId(winner.id())).isEmpty(); assertThat(bindings.findByUserId(loser.id())).isEmpty();
            assertThat(send(client, token, "/equipment/provider/borrows", null).path("items").size()).isEqualTo(2);
        }
    }
    @Test void providerIdentityCannotSubscribeToAnyLegacyWebSocketNamespace() throws Exception {
        var admin = accounts().adminLogin();
        try (var client = HttpClient.newHttpClient()) {
            var provider = provider(client, admin.token());
            String token = provider.path("token").asText();
            for (String path : List.of("/ws/web", "/ws/drone?deviceId=not-a-real-device", "/ws/" + provider.path("provider").path("providerId").asLong())) {
                assertThatThrownBy(() -> client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                        .buildAsync(URI.create(wsBaseUrl() + path), new WebSocket.Listener() { }).get(15, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(WebSocketHandshakeException.class);
            }
        }
    }
}

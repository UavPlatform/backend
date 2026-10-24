package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.RealProtocolTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

/** R4/R5: committed requests over TCP, not rollback-only concurrency simulations. */
@Import(DispatchTestData.AssessmentFixture.class)
@TestPropertySource(properties = "wechat.pay.mock-enabled=true")
class DispatchOrderConcurrencyE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private HttpRequest request(String token, String path, String method, String body) {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        return builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    @Test void duplicateCreatesPaymentsAndRefundsHaveOneStableOrderAndLedgerReference() throws Exception {
        var owner = accounts().registerUser();
        try (var client = HttpClient.newHttpClient()) {
            String draft = json.writeValueAsString(DispatchTestData.waybill(DispatchTestData.fields()));
            String assessment = data(client.send(request(owner.token(), "/dispatch/assessments", "POST", draft),
                    HttpResponse.BodyHandlers.ofString())).path("assessmentId").asText();
            String input = json.writeValueAsString(Map.of("assessmentId", assessment,
                    "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true,
                    "waybill", DispatchTestData.waybill(DispatchTestData.fields())));
            var create = request(owner.token(), "/dispatch/orders", "POST", input);
            var a = client.sendAsync(create, HttpResponse.BodyHandlers.ofString());
            var b = client.sendAsync(create, HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(a, b).join();
            String id = data(a.get()).path("orderId").asText();
            assertThat(data(b.get()).path("orderId").asText()).isEqualTo(id);
            for (String action : new String[]{"simulate-payment", "cancel"}) {
                var command = request(owner.token(), "/dispatch/orders/" + id + "/" + action, "POST", "");
                var first = client.sendAsync(command, HttpResponse.BodyHandlers.ofString());
                var second = client.sendAsync(command, HttpResponse.BodyHandlers.ofString());
                CompletableFuture.allOf(first, second).join();
                String field = action.equals("cancel") ? "refundReference" : "paymentReference";
                assertThat(data(first.get()).path(field).asText()).isEqualTo(data(second.get()).path(field).asText()).isNotBlank();
            }
            var result = data(client.send(request(owner.token(), "/dispatch/orders/" + id, "GET", ""),
                    HttpResponse.BodyHandlers.ofString()));
            assertThat(result.path("stage").asText()).isEqualTo("CANCELLED");
            assertThat(result.path("fundsStatus").asText()).isEqualTo("REFUNDED");
            assertThat(data(client.send(request(owner.token(), "/dispatch/orders", "GET", ""),
                    HttpResponse.BodyHandlers.ofString())).size()).isEqualTo(1);
        }
    }
    @Test void racingPayAndCancelCannotLeavePaidCancelledOrderWithoutRefund() throws Exception {
        var owner = accounts().registerUser();
        try (var client = HttpClient.newHttpClient()) {
            String draft = json.writeValueAsString(DispatchTestData.waybill(DispatchTestData.fields()));
            String assessment = data(client.send(request(owner.token(), "/dispatch/assessments", "POST", draft),
                    HttpResponse.BodyHandlers.ofString())).path("assessmentId").asText();
            String input = json.writeValueAsString(Map.of("assessmentId", assessment, "requestKey", UUID.randomUUID().toString(),
                    "declarationAccepted", true, "waybill", DispatchTestData.waybill(DispatchTestData.fields())));
            String id = data(client.send(request(owner.token(), "/dispatch/orders", "POST", input),
                    HttpResponse.BodyHandlers.ofString())).path("orderId").asText();
            var pay = client.sendAsync(request(owner.token(), "/dispatch/orders/" + id + "/simulate-payment", "POST", ""), HttpResponse.BodyHandlers.ofString());
            var cancel = client.sendAsync(request(owner.token(), "/dispatch/orders/" + id + "/cancel", "POST", ""), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(pay, cancel).join();
            assertThat(cancel.get().statusCode()).isEqualTo(200);
            assertThat(pay.get().statusCode()).isIn(200, 409);
            var result = data(client.send(request(owner.token(), "/dispatch/orders/" + id, "GET", ""), HttpResponse.BodyHandlers.ofString()));
            assertThat(result.path("stage").asText()).isEqualTo("CANCELLED");
            assertThat(result.path("fundsStatus").asText()).isEqualTo(pay.get().statusCode() == 200 ? "REFUNDED" : "UNPAID");
        }
    }
}

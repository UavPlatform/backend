package com.uav.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.RealProtocolTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Full demo uses the real production bean wiring, with only explicit test/demo configuration. */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchDemoE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Test void registeredUserCanAssessCreatePayResumeAndCancelInDemoMode() throws Exception {
        var owner = accounts().registerUser();
        try (var client = HttpClient.newHttpClient()) {
            var readiness = send(client, owner.token(), "/dispatch/readiness", null);
            assertThat(readiness.path("orderingEnabled").asBoolean()).isTrue();
            var fields = DispatchTestData.waybill(DispatchTestData.fields());
            var assessment = send(client, owner.token(), "/dispatch/assessments", fields);
            assertThat(assessment.path("evaluation").path("source").asText()).isEqualTo("SIMULATED_DEMO");
            var order = send(client, owner.token(), "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                    "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", fields));
            String path = "/dispatch/orders/" + order.path("orderId").asText();
            assertThat(order.path("fundsStatus").asText()).isEqualTo("UNPAID");
            assertThat(send(client, owner.token(), path + "/simulate-payment", Map.of()).path("stage").asText()).isEqualTo("WAITING_ACCEPTANCE");
            assertThat(send(client, owner.token(), path, null).path("fundsStatus").asText()).isEqualTo("PAID");
            assertThat(send(client, owner.token(), "/dispatch/orders", null).size()).isEqualTo(1);
            var cancelled = send(client, owner.token(), path + "/cancel", Map.of());
            assertThat(cancelled.path("stage").asText()).isEqualTo("CANCELLED");
            assertThat(cancelled.path("fundsStatus").asText()).isEqualTo("REFUNDED");
        }
    }
    private com.fasterxml.jackson.databind.JsonNode send(HttpClient client, String token, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        if (body != null) builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
}

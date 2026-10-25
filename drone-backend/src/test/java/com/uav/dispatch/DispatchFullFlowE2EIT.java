package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.DispatchMaterialRepository;
import com.uav.server.config.DispatchPaymentConfig;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@Import(DispatchObjectStoreFixture.Config.class)
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true", "order.price-per-meter=0.01"})
class DispatchFullFlowE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private final byte[] photo = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZAAAAABJRU5ErkJggg==");
    @Autowired DispatchMaterialRepository materials;
    @Autowired DispatchObjectStoreFixture storage;
    @Autowired DispatchPaymentConfig config;
    private HttpResponse<String> response(HttpClient client, Account actor, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).header("Authorization", "Bearer " + actor.token()).header("Content-Type", "application/json");
        if (body != null) builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode send(HttpClient client, Account actor, String path, Object body) throws Exception {
        var result = response(client, actor, path, body); assertThat(result.statusCode()).isEqualTo(200);
        return json.readTree(result.body()).path("data");
    }
    private long revision(HttpClient client, Account pilot, String id) throws Exception {
        for (int page = 0; ; page++) {
            var hall = send(client, pilot, "/dispatch/pilot/hall?page=" + page, null);
            for (var item : hall.path("items")) if (item.path("orderId").asText().equals(id)) return item.path("offerRevision").asLong();
            assertThat(hall.path("hasNext").asBoolean()).isTrue();
        }
    }
    private void put(HttpClient client, JsonNode ticket) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(ticket.path("uploadUrl").asText()))
                .header("Content-Type", ticket.path("contentType").asText()).PUT(HttpRequest.BodyPublishers.ofByteArray(photo)).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
    private JsonNode ticket(HttpClient client, Account actor, String base, String purpose) throws Exception {
        return send(client, actor, base + "/materials/upload-ticket", Map.of("purpose", purpose, "fileName", "proof.png", "contentType", "image/png", "sizeBytes", photo.length));
    }
    @Test void distanceQuoteRealUploadsDualMaterialsAndIdempotentBalancePaymentCompleteWholeOrder() throws Exception {
        runFlow(false, false);
    }
    @Test void overpaidDepositIsAutomaticallyRefundedAndZeroBalanceNeedsNoSecondPayment() throws Exception {
        runFlow(true, false); runFlow(false, true);
    }
    private void runFlow(boolean refund, boolean zero) throws Exception {
        var owner = accounts().registerUser(); var pilot = accounts().registerRider(UniqueNames.userName("flowpilot"), null);
        var intruder = accounts().registerUser();
        BigDecimal oldDeposit = config.getDepositAmount(); BigDecimal oldFee = config.getPilotServiceFee();
        try (var client = HttpClient.newHttpClient()) {
            if (refund) config.setDepositAmount(new BigDecimal("99999.00"));
            var waybill = DispatchTestData.waybill(DispatchTestData.fields());
            var assessment = send(client, owner, "/dispatch/assessments", waybill);
            if (zero) {
                config.setDepositAmount(new BigDecimal(assessment.path("quote").path("totalAmount").asText()));
                assessment = send(client, owner, "/dispatch/assessments", waybill);
            }
            var quote = assessment.path("quote");
            assertThat(quote.path("rule").asText()).isEqualTo("DISTANCE_V1");
            assertThat(quote.path("distanceMeters").decimalValue()).isNotEqualByComparingTo("1000");
            assertThat(quote.path("totalAmount").decimalValue()).isEqualByComparingTo(
                    quote.path("distanceCharge").decimalValue().add(quote.path("pilotServiceFee").decimalValue()));
            // Changing live configuration after assessment must not silently change the accepted quote.
            config.setPilotServiceFee(new BigDecimal("888.00"));
            String id = send(client, owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                    "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill)).path("orderId").asText();
            config.setPilotServiceFee(oldFee);
            String base = "/dispatch/orders/" + id; String pilotBase = "/dispatch/pilot/orders/" + id;
            assertThat(send(client, owner, base, null).path("bill").path("quote").path("pilotServiceFee").decimalValue())
                    .isEqualByComparingTo(quote.path("pilotServiceFee").decimalValue());
            send(client, owner, base + "/simulate-payment", Map.of());
            long epoch = send(client, pilot, pilotBase + "/claim", Map.of("offerRevision", revision(client, pilot, id))).path("assignmentVersion").asLong();
            var version = Map.of("assignmentVersion", epoch);
            assertThat(response(client, pilot, base + "/start-transport", version).statusCode()).isEqualTo(409);
            send(client, pilot, pilotBase + "/arrive", version);
            String serial = UniqueNames.djiId();
            var connection = send(client, pilot, pilotBase + "/flight-connections", Map.of("assignmentVersion", epoch,
                    "requestKey", UUID.randomUUID().toString(), "serialNumber", serial, "modelCode", "FC30", "modelName", "DJI FlyCart 30"));
            // ADR-0007 #6/#9: the order's drone is what djifly reported on connection.
            assertThat(send(client, owner, base, null).path("executedDrone").path("serialNumber").asText()).isEqualTo(serial);
            String sampling = pilotBase + "/flight-connections/" + connection.path("connectionId").asText() + "/samples";
            send(client, pilot, sampling, Map.of("sequence", 0, "capturedAt", System.currentTimeMillis(), "latitude", 28.2, "longitude", 113.0));
            assertThat(send(client, owner, base + "/flight", null).path("latest").path("battery").isNull()).isTrue();
            assertThat(send(client, pilot, base + "/start-transport", version).path("stage").asText()).isEqualTo("IN_TRANSIT");
            send(client, pilot, base + "/start-transport", version); // Retry must not create a second event.
            var proof = ticket(client, pilot, base, "PILOT_DELIVERY"); String proofId = proof.path("materialId").asText();
            assertThat(response(client, pilot, base + "/materials/" + proofId + "/confirm", Map.of()).statusCode()).isEqualTo(400);
            assertThat(response(client, pilot, base + "/deliver", Map.of("assignmentVersion", epoch, "materialIds", List.of(proofId))).statusCode()).isEqualTo(400);
            put(client, proof); send(client, pilot, base + "/materials/" + proofId + "/confirm", Map.of());
            send(client, pilot, base + "/deliver", Map.of("assignmentVersion", epoch, "materialIds", List.of(proofId)));
            send(client, pilot, base + "/deliver", Map.of("assignmentVersion", epoch, "materialIds", List.of(proofId)));
            assertThat(send(client, owner, base, null).path("stage").asText()).isEqualTo("DELIVERY_CONFIRMATION");
            var flight = send(client, owner, base + "/flight", null);
            assertThat(flight.path("connection").path("endReason").asText()).isEqualTo("DELIVERED");
            assertThat(flight.path("stale").asBoolean()).isTrue();
            assertThat(response(client, pilot, sampling, Map.of("sequence", 1, "capturedAt", System.currentTimeMillis())).statusCode()).isEqualTo(409);
            assertThat(response(client, intruder, base + "/materials", null).statusCode()).isEqualTo(404);
            assertThat(response(client, owner, base + "/receipt", Map.of("materialIds", List.of(proofId))).statusCode()).isEqualTo(400);
            var receipt = ticket(client, owner, base, "SHIPPER_RECEIPT"); String receiptId = receipt.path("materialId").asText();
            put(client, receipt); send(client, owner, base + "/materials/" + receiptId + "/confirm", Map.of());
            // A previously confirmed object disappearing must still block final closure.
            storage.remove(materials.findById(proofId).orElseThrow().getObjectKey());
            assertThat(response(client, owner, base + "/receipt", Map.of("materialIds", List.of(receiptId))).statusCode()).isEqualTo(400);
            put(client, proof);
            var done = send(client, owner, base + "/receipt", Map.of("materialIds", List.of(receiptId)));
            send(client, owner, base + "/receipt", Map.of("materialIds", List.of(receiptId)));
            assertThat(done.path("stage").asText()).isEqualTo("COMPLETED");
            assertThat(done.path("bill").path("finalAmount").decimalValue()).isEqualByComparingTo(quote.path("totalAmount").decimalValue());
            assertThat(done.path("fundsStatus").asText()).isEqualTo(refund || zero ? "SETTLED" : "BALANCE_DUE");
            if (refund) assertThat(done.path("refundReference").asText()).isEqualTo("sim-difference-" + id);
            for (int i = 0; i < 2; i++) assertThat(send(client, owner, base + "/settle", Map.of()).path("fundsStatus").asText()).isEqualTo("SETTLED");
            for (int i = 0; i < 2; i++) assertThat(send(client, owner, base + "/review", Map.of("score", 5, "comment", "已验收"))
                    .path("bill").path("reviewScore").asInt()).isEqualTo(5);
            var inbox = send(client, owner, "/dispatch/notifications", null);
            assertThat(inbox.path("items").size()).isEqualTo(refund || zero ? 6 : 7);
            Set<String> kinds = new HashSet<>();
            inbox.path("items").forEach(n -> {
                assertThat(n.path("orderId").asText()).isEqualTo(id);
                assertThat(kinds.add(n.path("kind").asText())).isTrue();
            });
            assertThat(kinds).contains("TRANSPORT_STARTED", "DELIVERED", "COMPLETED");
            assertThat(send(client, pilot, "/dispatch/notifications", null).path("items").get(0).path("kind").asText()).isEqualTo("COMPLETED");
            assertThat(response(client, pilot, base + "/settle", Map.of()).statusCode()).isEqualTo(403);
            assertThat(response(client, owner, base + "/cancel", Map.of()).statusCode()).isEqualTo(409);
        } finally { config.setDepositAmount(oldDeposit); config.setPilotServiceFee(oldFee); }
    }
}

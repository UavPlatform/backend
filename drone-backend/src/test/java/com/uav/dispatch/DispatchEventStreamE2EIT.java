package com.uav.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.service.DispatchNotificationService;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchEventStreamE2EIT extends RealProtocolTestBase {
    @Autowired DispatchNotificationService notifications;
    @Autowired DispatchOrderRepository orders;
    @Autowired PlatformTransactionManager transactions;
    private final ObjectMapper json = new ObjectMapper();

    private String post(HttpClient client, Account owner, String path, Object body, String field) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + owner.token()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path(field).asText();
    }

    @Test void authenticatedStreamSeesCommittedOrdersButNeverRollbackOrOtherOwners() throws Exception {
        var owner = accounts().registerUser(); var stranger = accounts().registerUser();
        try (var client = HttpClient.newHttpClient(); var own = open(client, owner); var other = open(client, stranger)) {
            assertThat(own.next(3000)).isEqualTo(new Frame("ready", "{}"));
            assertThat(other.next(3000)).isEqualTo(new Frame("ready", "{}"));
            var waybill = DispatchTestData.waybill(DispatchTestData.fields());
            String assessment = post(client, owner, "/dispatch/assessments", waybill, "assessmentId");
            String id = post(client, owner, "/dispatch/orders", Map.of("assessmentId", assessment,
                    "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill), "orderId");
            assertThat(own.next(3000)).isEqualTo(new Frame("change", "{}"));
            assertThat(other.next(400)).isNull();
            var tx = new TransactionTemplate(transactions);
            tx.executeWithoutResult(status -> {
                var order = orders.findById(id).orElseThrow();
                order.setUpdatedAt(LocalDateTime.now()); notifications.changed(order); status.setRollbackOnly();
            });
            assertThat(own.next(600)).isNull();
            tx.executeWithoutResult(status -> {
                var order = orders.findById(id).orElseThrow();
                order.setUpdatedAt(LocalDateTime.now()); notifications.changed(order);
            });
            assertThat(own.next(3000)).isEqualTo(new Frame("change", "{}"));
            assertThat(other.next(400)).isNull();
        }
        try (var client = HttpClient.newHttpClient(); var reopened = open(client, owner)) {
            assertThat(reopened.next(3000)).isEqualTo(new Frame("ready", "{}"));
        }
    }

    @Test void queryTokensCannotReplaceBearerAuthentication() throws Exception {
        var owner = accounts().registerUser();
        try (var client = HttpClient.newHttpClient()) {
            for (var path : new String[]{"/dispatch/events", "/dispatch/events?token=" + owner.token()}) {
                var result = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                assertThat(result.statusCode()).isEqualTo(401);
            }
        }
    }

    private WireStream open(HttpClient client, Account owner) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/dispatch/events"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + owner.token())
                .header("Accept", "text/event-stream").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("x-accel-buffering")).contains("no");
        return new WireStream(response.body());
    }
    private record Frame(String event, String data) { }
    private static final class WireStream implements AutoCloseable {
        private final Stream<String> lines;
        private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "test-sse-reader"); thread.setDaemon(true); return thread;
        });
        WireStream(Stream<String> lines) {
            this.lines = lines;
            reader.submit(() -> {
                try {
                    String event = "message"; var data = new StringBuilder();
                    var iterator = lines.iterator();
                    while (iterator.hasNext()) {
                        String line = iterator.next();
                        if (line.startsWith("event:")) event = line.substring(6).strip();
                        if (line.startsWith("data:")) data.append(line.substring(5).strip());
                        if (line.isEmpty()) { frames.offer(new Frame(event, data.toString())); event = "message"; data.setLength(0); }
                    }
                } catch (Exception ignored) { /* Client deliberately closes the stream. */ }
            });
        }
        Frame next(long millis) throws InterruptedException { return frames.poll(millis, TimeUnit.MILLISECONDS); }
        @Override public void close() { lines.close(); reader.shutdownNow(); }
    }
}

package com.uav.dispatch;

import com.uav.support.RealProtocolTestBase;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Real committed HTTP requests: exactly one device wins for each draft revision. */
class DispatchDraftConcurrencyE2EIT extends RealProtocolTestBase {
    @Test
    void simultaneousCreatesAndUpdatesCannotOverwriteEachOther() throws Exception {
        var owner = accounts().registerUser();
        String create = "{\"schemaVersion\":1,\"step\":0,\"fields\":{\"category\":\"普通\"}}";
        assertOneWinner(owner.token(), create);
        String update = "{\"schemaVersion\":1,\"step\":1,\"fields\":{},\"expectedRevision\":0}";
        assertOneWinner(owner.token(), update);
    }

    private void assertOneWinner(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/dispatch/waybill-draft"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var first = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(first, second).join();
            assertThat(List.of(first.get().statusCode(), second.get().statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }
}

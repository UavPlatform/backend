package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.DispatchAssignmentRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REQ-ALL-XC-004 / ADR-0013: real persisted times, idempotency and assignment isolation. */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchTimelineIT extends IntegrationTestBase {
    @Autowired DispatchOrderRepository orders;
    @Autowired DispatchAssignmentRepository assignments;
    @Autowired EntityManager em;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode postData(Account actor, String path, Object body) throws Exception {
        return json.readTree(mockMvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode getData(Account actor, String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode create(Account owner) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = postData(owner, "/dispatch/assessments", waybill);
        return postData(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill));
    }
    private LocalDateTime time(JsonNode node, String field) { return LocalDateTime.parse(node.path(field).asText()); }

    @Test void paymentAndCurrentAssignmentTimesSurviveRetriesAndRedispatch() throws Exception {
        var owner = accounts().registerUser();
        var first = accounts().registerRider(UniqueNames.userName("timeline"), null);
        var second = accounts().registerRider(UniqueNames.userName("timeline"), null);
        var created = create(owner);
        String id = created.path("orderId").asText();
        String path = "/dispatch/pilot/orders/" + id;
        assertThat(created.path("depositPaidAt").asText(null)).isNull();
        var paid = postData(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of());
        var paidAt = time(paid, "depositPaidAt");
        assertThat(time(paid, "waitingSince")).isEqualTo(paidAt);
        assertThat(time(postData(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of()), "depositPaidAt"))
                .isEqualTo(paidAt);
        var offer = Map.of("offerRevision", orders.findById(id).orElseThrow().getRevision());
        var claimed = postData(first, path + "/claim", offer);
        long version = claimed.path("assignmentVersion").asLong();
        var acceptedAt = time(claimed, "acceptedAt");
        assertThat(time(postData(first, path + "/claim", offer), "acceptedAt")).isEqualTo(acceptedAt);
        var arrived = postData(first, path + "/arrive", Map.of("assignmentVersion", version));
        var arrivedAt = time(arrived, "arrivedAt");
        assertThat(time(postData(first, path + "/arrive", Map.of("assignmentVersion", version)), "arrivedAt"))
                .isEqualTo(arrivedAt);
        em.flush(); em.clear();
        var assignment = assignments.findByOrderIdAndAssignmentVersion(id, version).orElseThrow();
        assertThat(assignment.getAcceptedAt()).isEqualTo(acceptedAt);
        assertThat(assignment.getArrivedAt()).isEqualTo(arrivedAt);
        assertThat(time(getData(owner, "/dispatch/orders/" + id), "arrivedAt")).isEqualTo(arrivedAt);
        postData(first, path + "/release", Map.of("assignmentVersion", version));
        var waiting = getData(owner, "/dispatch/orders/" + id);
        var waitingSince = time(waiting, "waitingSince");
        assertThat(waitingSince).isAfterOrEqualTo(arrivedAt);
        assertThat(waiting.path("acceptedAt").asText(null)).isNull();
        assertThat(waiting.path("arrivedAt").asText(null)).isNull();
        var replaced = postData(second, path + "/claim",
                Map.of("offerRevision", orders.findById(id).orElseThrow().getRevision()));
        assertThat(time(replaced, "acceptedAt")).isAfterOrEqualTo(waitingSince);
        assertThat(replaced.path("arrivedAt").asText(null)).isNull();
        assertThat(time(replaced, "waitingSince")).isEqualTo(waitingSince);
        assertThat(time(replaced, "depositPaidAt")).isEqualTo(paidAt);
        assertThat(time(getData(second, path), "acceptedAt")).isEqualTo(time(replaced, "acceptedAt"));
        for (var listPath : new String[]{"/dispatch/orders", "/dispatch/pilot/orders"}) {
            var actor = listPath.contains("/pilot/") ? second : owner;
            var rows = getData(actor, listPath);
            assertThat(rows).anySatisfy(row -> {
                assertThat(row.path("orderId").asText()).isEqualTo(id);
                assertThat(time(row, "acceptedAt")).isEqualTo(time(replaced, "acceptedAt"));
            });
        }
    }

    @Test void auditSuperclassGeneratesTimesAndCreationCannotBeRewrittenOnUpdate() throws Exception {
        var owner = accounts().registerUser();
        String id = create(owner).path("orderId").asText();
        em.flush(); em.clear();
        var order = orders.findById(id).orElseThrow();
        var createdAt = order.getCreatedAt();
        assertThat(createdAt).isNotNull();
        assertThat(order.getUpdatedAt()).isEqualTo(createdAt);
        order.setCreatedAt(createdAt.minusYears(1));
        order.setUpdatedAt(createdAt.minusDays(1));
        order.setReviewComment("audit lifecycle check");
        orders.saveAndFlush(order); em.clear();
        var stored = orders.findById(id).orElseThrow();
        assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
        assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(createdAt);
        assertThat(stored.getDepositPaidAt()).isNull();
        assertThat(stored.getWaitingSince()).isNull();
    }
}

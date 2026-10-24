package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.service.DispatchNotificationService;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchNotificationIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired DispatchNotificationService notices;
    @Autowired DispatchOrderRepository orders;
    @Autowired PlatformTransactionManager transactions;
    private JsonNode postData(Account actor, String path, Object body) throws Exception {
        var response = mockMvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    private JsonNode getData(Account actor, String path) throws Exception {
        var response = mockMvc.perform(get(path).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    private String create(Account owner) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = postData(owner, "/dispatch/assessments", waybill);
        return postData(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill)).path("orderId").asText();
    }
    private long offer(Account pilot, String id) {
        return orders.findById(id).orElseThrow().getRevision();
    }
    @Test void realEventsAreUniqueRecipientScopedAndReadingOneNeverClearsOthers() throws Exception {
        var owner = accounts().registerUser(); var stranger = accounts().registerUser();
        var a = accounts().registerRider(UniqueNames.userName("noticepilot"), null);
        var b = accounts().registerRider(UniqueNames.userName("noticepilot"), null);
        String id = create(owner); String base = "/dispatch/orders/" + id; String pilot = "/dispatch/pilot/orders/" + id;
        for (int i = 0; i < 2; i++) postData(owner, base + "/simulate-payment", Map.of());
        var input = Map.of("offerRevision", offer(a, id));
        long epoch = postData(a, pilot + "/claim", input).path("assignmentVersion").asLong();
        postData(a, pilot + "/claim", input);
        for (int i = 0; i < 2; i++) postData(a, pilot + "/arrive", Map.of("assignmentVersion", epoch));
        for (int i = 0; i < 2; i++) postData(a, pilot + "/release", Map.of("assignmentVersion", epoch));
        postData(b, pilot + "/claim", Map.of("offerRevision", offer(b, id)));
        for (int i = 0; i < 2; i++) postData(owner, base + "/cancel", Map.of());
        var inbox = getData(owner, "/dispatch/notifications");
        assertThat(inbox.path("items").size()).isEqualTo(6);
        assertThat(inbox.path("unreadCount").asLong()).isEqualTo(6);
        var newest = inbox.path("items").get(0);
        assertThat(newest.path("kind").asText()).isEqualTo("CANCELLED");
        assertThat(newest.path("orderId").asText()).isEqualTo(id);
        assertThat(getData(owner, base).path("stage").asText()).isEqualTo("CANCELLED");
        String read = "/dispatch/notifications/" + newest.path("notificationId").asText() + "/read";
        mockMvc.perform(post(read).header("Authorization", "Bearer " + stranger.token())).andExpect(status().isNotFound());
        assertThat(getData(stranger, "/dispatch/notifications/unread-count").asLong()).isZero();
        for (int i = 0; i < 2; i++) assertThat(postData(owner, read, Map.of()).isNull()).isTrue();
        assertThat(getData(owner, "/dispatch/notifications/unread-count").asLong()).isEqualTo(5);
        var mine = getData(b, "/dispatch/notifications").path("items");
        assertThat(mine.size()).isEqualTo(1); assertThat(mine.get(0).path("audience").asText()).isEqualTo("PILOT");
        assertThat(getData(a, "/dispatch/notifications").path("items").size()).isZero();
    }
    // This test must actually end its rollback transaction; the base test transaction would only mark it rollback-only.
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackDoesNotPublishAndPagingDoesNotMarkAnythingRead() throws Exception {
        var owner = accounts().registerUser(); String id = create(owner);
        var order = orders.findById(id).orElseThrow();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            notices.owner(order, "ROLLBACK", "回滚测试", "不应出现"); status.setRollbackOnly();
        });
        assertThat(getData(owner, "/dispatch/notifications").path("items").size()).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int i = 0; i < 25; i++) notices.owner(order, "PAGE_" + i, "分页测试", "测试通知");
        });
        var first = getData(owner, "/dispatch/notifications?page=0");
        var second = getData(owner, "/dispatch/notifications?page=1");
        assertThat(first.path("items").size()).isEqualTo(20); assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(second.path("items").size()).isEqualTo(5); assertThat(second.path("hasNext").asBoolean()).isFalse();
        Set<String> seen = new HashSet<>();
        first.path("items").forEach(n -> seen.add(n.path("notificationId").asText()));
        second.path("items").forEach(n -> assertThat(seen.add(n.path("notificationId").asText())).isTrue());
        assertThat(getData(owner, "/dispatch/notifications/unread-count").asLong()).isEqualTo(25);
        for (String page : List.of("-1", "oops", "1000001")) mockMvc.perform(get("/dispatch/notifications?page=" + page)
                .header("Authorization", "Bearer " + owner.token())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/dispatch/notifications")).andExpect(status().isUnauthorized());
    }
}

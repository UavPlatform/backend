package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REQ-SHIP-EXEC-002 / REQ-PILOT-EXEC-006: real auth and persistence, including legacy chat paths. */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchChatIT extends IntegrationTestBase {
    @Autowired DispatchOrderRepository orders;
    @Autowired ChatSessionRepository sessions;
    private final ObjectMapper json = new ObjectMapper();
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
    private Account pilot() throws Exception { return accounts().registerRider(UniqueNames.userName("chatpilot"), null); }
    private String assigned(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = postData(owner, "/dispatch/assessments", waybill);
        String id = postData(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill)).path("orderId").asText();
        postData(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of());
        claim(pilot, id);
        return id;
    }
    private long claim(Account pilot, String id) throws Exception {
        return postData(pilot, "/dispatch/pilot/orders/" + id + "/claim",
                Map.of("offerRevision", orders.findById(id).orElseThrow().getRevision())).path("assignmentVersion").asLong();
    }
    private long open(Account actor, String id, long version) throws Exception {
        var data = postData(actor, "/dispatch/orders/" + id + "/chat", Map.of("assignmentVersion", version));
        assertThat(data.path("orderId").asText()).isEqualTo(id);
        assertThat(data.path("assignmentVersion").asLong()).isEqualTo(version);
        return data.path("sessionId").asLong();
    }
    private void forbidden(Account actor, long session) throws Exception {
        mockMvc.perform(get("/chat/Message/messages/" + session).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/chat/Message/send").header("Authorization", "Bearer " + actor.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("sessionId", session, "content", "拒绝发送"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/chat/Message/read").param("sessionId", Long.toString(session))
                .header("Authorization", "Bearer " + actor.token())).andExpect(status().isForbidden());
    }
    @Test void bothParticipantsReuseOneConversationAndStrangersCannotReadOrSend() throws Exception {
        var owner = accounts().registerUser(); var pilot = pilot(); var stranger = pilot();
        String id = assigned(owner, pilot); long session = open(owner, id, 1);
        assertThat(open(pilot, id, 1)).isEqualTo(session);
        assertThat(sessions.findById(session).orElseThrow().getTaskNum()).isNull();
        postData(owner, "/chat/Message/send", Map.of("sessionId", session, "content", "请核对吊点"));
        assertThat(getData(pilot, "/chat/Message/messages/" + session).get(0).path("payload").path("text").asText()).isEqualTo("请核对吊点");
        forbidden(stranger, session);
        mockMvc.perform(post("/dispatch/orders/" + id + "/chat").header("Authorization", "Bearer " + stranger.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"assignmentVersion\":1}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/dispatch/orders/" + id + "/chat").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"assignmentVersion\":2}")).andExpect(status().isConflict());
        mockMvc.perform(post("/dispatch/orders/" + id + "/chat")).andExpect(status().isUnauthorized());
    }
    @Test void releasedAssignmentIsRemovedFromListsUnreadAndOfflineSyncAndNewPilotGetsNoHistory() throws Exception {
        var owner = accounts().registerUser(); var a = pilot(); var b = pilot();
        String id = assigned(owner, a); long old = open(owner, id, 1);
        postData(owner, "/chat/Message/send", Map.of("sessionId", old, "content", "仅第一轮可见"));
        postData(a, "/dispatch/pilot/orders/" + id + "/release", Map.of("assignmentVersion", 1));
        forbidden(a, old); forbidden(owner, old);
        assertThat(getData(a, "/chat/session/list").size()).isZero();
        assertThat(getData(a, "/chat/Message/sync").size()).isZero();
        assertThat(getData(a, "/chat/Message/unread/count").size()).isZero();
        assertThat(claim(b, id)).isEqualTo(2);
        long next = open(b, id, 2); assertThat(next).isNotEqualTo(old);
        assertThat(getData(b, "/chat/Message/messages/" + next).size()).isZero();
        forbidden(a, next); forbidden(owner, old);
    }
    @Test void samePilotReclaimsWithoutReusingOldChatAndGeneralDirectChatDoesNotReuseDispatchSession() throws Exception {
        var owner = accounts().registerUser(); var pilot = pilot(); String id = assigned(owner, pilot);
        long old = open(pilot, id, 1);
        postData(pilot, "/dispatch/pilot/orders/" + id + "/release", Map.of("assignmentVersion", 1));
        claim(pilot, id); long next = open(owner, id, 2);
        assertThat(next).isNotEqualTo(old); forbidden(pilot, old);
        long pilotId = orders.findById(id).orElseThrow().getAssignedPilotId();
        long direct = postData(owner, "/chat/session/create", Map.of("name", "普通会话", "type", 0, "userIds", List.of(pilotId))).path("id").asLong();
        assertThat(direct).isNotIn(old, next);
        postData(owner, "/chat/session/delete/" + next, Map.of());
        assertThat(sessions.existsById(next)).isTrue();
    }
    @Test void cancelledOrderRejectsPreviouslyIssuedSessionAndCompletedOrderIsReadOnly() throws Exception {
        var owner = accounts().registerUser(); var pilot = pilot(); String id = assigned(owner, pilot);
        long session = open(owner, id, 1);
        postData(owner, "/chat/Message/send", Map.of("sessionId", session, "content", "保留已发送记录"));
        String messageId = getData(owner, "/chat/Message/messages/" + session).get(0).path("msgId").asText();
        // State fixture isolates chat permissions; complete fulfillment is covered by its own E2E tests.
        var order = orders.findById(id).orElseThrow(); order.setStage("COMPLETED"); orders.saveAndFlush(order);
        assertThat(open(owner, id, 1)).isEqualTo(session);
        assertThat(getData(pilot, "/chat/Message/messages/" + session).size()).isEqualTo(1);
        for (String action : List.of("recall", "delete")) mockMvc.perform(post("/chat/Message/" + action + "/" + messageId)
                .header("Authorization", "Bearer " + owner.token())).andExpect(status().isForbidden());
        mockMvc.perform(post("/chat/Message/send").header("Authorization", "Bearer " + pilot.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("sessionId", session, "content", "结单后不可发送"))))
                .andExpect(status().isForbidden());
        order.setStage("PILOT_PREPARING"); orders.saveAndFlush(order);
        postData(owner, "/dispatch/orders/" + id + "/cancel", Map.of()); forbidden(pilot, session);
    }
}

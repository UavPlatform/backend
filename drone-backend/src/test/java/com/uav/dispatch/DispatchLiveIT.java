package com.uav.dispatch;

import com.fasterxml.jackson.databind.*;
import com.uav.support.*;
import com.uav.support.TestAccounts.Account;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REQ-SHIP-EXEC-004 / REQ-PILOT-EXEC-007: real HTTP authorization and persisted nullable samples. */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true", "trtc.sdk-app-id=1900000999", "trtc.secret-key=unit-test-only-signing-key", "dispatch.live.room-permissions-enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DispatchLiveIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired com.uav.live.service.TRTCService sharedTrtc;
    @org.springframework.beans.factory.annotation.Autowired com.uav.flight.mapper.DispatchLiveRepository sessions;
    private JsonNode send(Account actor, String path, Object body, int status) throws Exception {
        var r = mockMvc.perform(post(path).header("Authorization", "Bearer " + actor.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().is(status)).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("data");
    }
    private JsonNode read(Account actor, String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String prepare(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = send(owner, "/dispatch/assessments", waybill, 200);
        String id = send(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill), 200).path("orderId").asText();
        send(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of(), 200);
        JsonNode card = null;
        for (var item : read(pilot, "/dispatch/pilot/hall").path("items")) if (id.equals(item.path("orderId").asText())) card = item;
        assertThat(card).isNotNull();
        send(pilot, path(id) + "/claim", Map.of("offerRevision", card.path("offerRevision").asLong()), 200);
        send(pilot, path(id) + "/arrive", Map.of("assignmentVersion", 1), 200);
        return id;
    }
    private String path(String id) { return "/dispatch/pilot/orders/" + id; }
    private Map<String, Object> connect(String serial) {
        return Map.of("assignmentVersion", 1, "requestKey", UUID.randomUUID().toString(), "serialNumber", serial,
                "modelCode", "FC30", "modelName", "DJI FlyCart 30");
    }
    @Test void onlyCurrentPilotStartsAndOwnerReceivesRoomBoundReadOnlyTickets() throws Exception {
        var owner = accounts().registerUser(); var p = accounts().registerRider(UniqueNames.userName("live"), null);
        var stranger = accounts().registerUser(); String id = prepare(owner, p);
        String base = "/dispatch/orders/" + id;
        String cid = send(p, path(id) + "/flight-connections", connect(UniqueNames.djiId()), 200).path("connectionId").asText();
        String endpoint = path(id) + "/flight-connections/" + cid + "/live";
        var request = Map.of("requestKey", UUID.randomUUID().toString());
        send(owner, endpoint, request, 403);
        var ticket = send(p, endpoint, request, 200);
        assertThat(ticket.path("sdkAppId").asLong()).isEqualTo(sharedTrtc.getSdkAppId());
        String liveId = ticket.path("liveId").asText();
        assertThat(send(p, endpoint, request, 200).path("liveId").asText()).isEqualTo(liveId);
        assertThat(ticket.path("privateMapKey").asText()).isNotBlank();
        send(owner, base + "/live/" + liveId + "/watch", Map.of(), 409);
        send(p, base + "/start-transport", Map.of("assignmentVersion", 1), 200);
        assertThat(read(owner, base + "/live").path("state").asText()).isEqualTo("STARTING");
        send(p, endpoint + "/" + liveId + "/heartbeat", Map.of("state", "RUNNING"), 200);
        var status = read(owner, base + "/live"); assertThat(status.path("configured").asBoolean()).isTrue();
        assertThat(status.has("userSig")).isFalse(); assertThat(status.has("privateMapKey")).isFalse();
        var viewer = send(owner, base + "/live/" + liveId + "/watch", Map.of(), 200);
        assertThat(viewer.path("roomId").asText()).isEqualTo(ticket.path("roomId").asText());
        assertThat(viewer.path("userId").asText()).isNotEqualTo(ticket.path("userId").asText());
        assertThat(viewer.path("publisherUserId").asText()).isEqualTo(ticket.path("userId").asText());
        send(stranger, base + "/live/" + liveId + "/watch", Map.of(), 404);
        send(p, endpoint + "/" + liveId + "/stop", Map.of(), 200);
        var next = send(p, endpoint, Map.of("requestKey", UUID.randomUUID().toString()), 200);
        assertThat(next.path("roomId").asText()).isNotEqualTo(ticket.path("roomId").asText());
        send(p, endpoint + "/" + liveId + "/stop", Map.of(), 200);
        send(p, endpoint + "/" + liveId + "/heartbeat", Map.of("state", "RUNNING"), 409);
        assertThat(read(owner, base + "/live").path("liveId").asText()).isEqualTo(next.path("liveId").asText());
        send(owner, base + "/cancel", Map.of(), 200);
        assertThat(read(owner, base + "/live").path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(sessions.findById(next.path("liveId").asText()).orElseThrow().getEndedAt()).isNotNull();
        send(owner, base + "/live/" + next.path("liveId").asText() + "/watch", Map.of(), 409);
    }
    @Test void expiredHeartbeatAndDeviceReplacementCannotReviveOldRoom() throws Exception {
        var owner = accounts().registerUser(); var p = accounts().registerRider(UniqueNames.userName("live"), null);
        String id = prepare(owner, p), base = "/dispatch/orders/" + id;
        String cid = send(p, path(id) + "/flight-connections", connect(UniqueNames.djiId()), 200).path("connectionId").asText();
        send(p, base + "/start-transport", Map.of("assignmentVersion", 1), 200);
        String endpoint = path(id) + "/flight-connections/" + cid + "/live";
        var ticket = send(p, endpoint, Map.of("requestKey", UUID.randomUUID().toString()), 200);
        String liveId = ticket.path("liveId").asText();
        var expired = sessions.findById(liveId).orElseThrow(); expired.setLeaseExpiresAt(System.currentTimeMillis() - 1); sessions.saveAndFlush(expired);
        assertThat(read(owner, base + "/live").path("state").asText()).isEqualTo("UNAVAILABLE");
        send(p, endpoint + "/" + liveId + "/heartbeat", Map.of("state", "RUNNING"), 409);
        send(owner, base + "/live/" + liveId + "/watch", Map.of(), 409);
        var next = send(p, endpoint, Map.of("requestKey", UUID.randomUUID().toString()), 200);
        send(p, path(id) + "/flight-connections", connect(UniqueNames.djiId()), 200);
        assertThat(sessions.findById(next.path("liveId").asText()).orElseThrow().getEndedAt()).isNotNull();
        send(p, endpoint + "/" + next.path("liveId").asText() + "/heartbeat", Map.of("state", "RUNNING"), 409);
    }
}

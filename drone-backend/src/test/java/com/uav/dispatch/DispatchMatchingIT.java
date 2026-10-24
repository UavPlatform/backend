package com.uav.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.DispatchAssignmentRepository;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchMatchingIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired DispatchAssignmentRepository assignments;
    private Account pilot() { return accounts().registerRider(UniqueNames.userName("dispatchpilot"), null); }
    private JsonNode postData(Account actor, String path, Object body) throws Exception {
        var response = mockMvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    private String create(Account owner, boolean paid) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = postData(owner, "/dispatch/assessments", waybill);
        String id = postData(owner, "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill)).path("orderId").asText();
        if (paid) postData(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of());
        return id;
    }
    private JsonNode offer(Account pilot, String id) throws Exception {
        String response = mockMvc.perform(get("/dispatch/pilot/hall").header("Authorization", "Bearer " + pilot.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var item : json.readTree(response).path("data").path("items")) if (item.path("orderId").asText().equals(id)) return item;
        throw new AssertionError("Paid waiting order missing from hall");
    }
    @Test void unboundPilotClaimsArrivesReleasesAndAnotherPilotReclaimsRealOrder() throws Exception {
        var owner = accounts().registerUser(); var a = pilot(); var b = pilot();
        String id = create(owner, true); String path = "/dispatch/pilot/orders/" + id;
        var card = offer(a, id);
        assertThat(card.path("fields").has("start.phone")).isFalse();
        assertThat(card.path("fields").has("end.contact")).isFalse();
        assertThat(card.has("paymentReference")).isFalse();
        var claim = Map.of("offerRevision", card.path("offerRevision").asLong());
        var accepted = postData(a, path + "/claim", claim);
        long epoch = accepted.path("assignmentVersion").asLong();
        assertThat(accepted.path("assignedPilotId").asLong()).isEqualTo(a.id());
        assertThat(accepted.path("fields").path("start.phone").asText()).isNotBlank();
        assertThat(postData(a, path + "/claim", claim).path("assignmentVersion").asLong()).isEqualTo(epoch);
        mockMvc.perform(get(path).header("Authorization", "Bearer " + b.token())).andExpect(status().isNotFound());
        postData(a, path + "/arrive", Map.of("assignmentVersion", epoch));
        mockMvc.perform(get("/dispatch/orders/" + id).header("Authorization", "Bearer " + owner.token()))
                .andExpect(jsonPath("$.data.stage").value("PILOT_ARRIVED"));
        for (int i = 0; i < 2; i++) assertThat(postData(a, path + "/release", Map.of("assignmentVersion", epoch)).isNull()).isTrue();
        mockMvc.perform(get(path).header("Authorization", "Bearer " + a.token())).andExpect(status().isNotFound());
        var replacement = postData(b, path + "/claim", Map.of("offerRevision", offer(b, id).path("offerRevision").asLong()));
        assertThat(replacement.path("assignmentVersion").asLong()).isGreaterThan(epoch);
        assertThat(replacement.path("fundsStatus").asText()).isEqualTo("PAID");
        // Old cancellation retry must not remove the new pilot.
        postData(a, path + "/release", Map.of("assignmentVersion", epoch));
        mockMvc.perform(get(path).header("Authorization", "Bearer " + b.token()))
                .andExpect(jsonPath("$.data.assignedPilotId").value(b.id()));
        assertThat(assignments.findByOrderIdAndAssignmentVersion(id, epoch).orElseThrow().getEndReason()).isEqualTo("PILOT_RELEASED");
    }
    @Test void oldOfferAndAssignmentCannotReclaimOrMutateLaterAssignment() throws Exception {
        var owner = accounts().registerUser(); var p = pilot();
        String id = create(owner, true); String path = "/dispatch/pilot/orders/" + id;
        long revision = offer(p, id).path("offerRevision").asLong();
        long epoch = postData(p, path + "/claim", Map.of("offerRevision", revision)).path("assignmentVersion").asLong();
        postData(p, path + "/release", Map.of("assignmentVersion", epoch));
        mockMvc.perform(post(path + "/claim").header("Authorization", "Bearer " + p.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("offerRevision", revision)))).andExpect(status().isConflict());
        var current = postData(p, path + "/claim", Map.of("offerRevision", offer(p, id).path("offerRevision").asLong()));
        postData(p, path + "/release", Map.of("assignmentVersion", epoch));
        mockMvc.perform(post(path + "/arrive").header("Authorization", "Bearer " + p.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("assignmentVersion", epoch)))).andExpect(status().isConflict());
        mockMvc.perform(get(path).header("Authorization", "Bearer " + p.token()))
                .andExpect(jsonPath("$.data.assignmentVersion").value(current.path("assignmentVersion").asLong()))
                .andExpect(jsonPath("$.data.stage").value("PILOT_PREPARING"));
    }
    @Test void shipperCancelsAssignedOrderAndPilotCannotReviveIt() throws Exception {
        var owner = accounts().registerUser(); var p = pilot();
        String id = create(owner, true); String path = "/dispatch/pilot/orders/" + id;
        var accepted = postData(p, path + "/claim", Map.of("offerRevision", offer(p, id).path("offerRevision").asLong()));
        long epoch = accepted.path("assignmentVersion").asLong();
        postData(owner, "/dispatch/orders/" + id + "/cancel", Map.of());
        mockMvc.perform(get(path).header("Authorization", "Bearer " + p.token()))
                .andExpect(jsonPath("$.data.stage").value("CANCELLED")).andExpect(jsonPath("$.data.fundsStatus").value("REFUNDED"));
        mockMvc.perform(post(path + "/arrive").header("Authorization", "Bearer " + p.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("assignmentVersion", epoch)))).andExpect(status().isConflict());
        assertThat(assignments.findByOrderIdAndAssignmentVersion(id, epoch).orElseThrow().getEndReason()).isEqualTo("SHIPPER_CANCELLED");
    }
    @Test void roleChecksUnpaidOrdersAndMalformedRevisionsCannotBeBypassed() throws Exception {
        var owner = accounts().registerUser(); var p = pilot();
        String id = create(owner, false); String path = "/dispatch/pilot/orders/" + id;
        mockMvc.perform(get("/dispatch/pilot/hall")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/dispatch/pilot/hall").header("Authorization", "Bearer " + owner.token())).andExpect(status().isForbidden());
        mockMvc.perform(post(path + "/claim").header("Authorization", "Bearer " + p.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerRevision\":0}")).andExpect(status().isConflict());
        mockMvc.perform(post(path + "/claim").header("Authorization", "Bearer " + p.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerRevision\":-1}")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/dispatch/pilot/hall?page=-1").header("Authorization", "Bearer " + p.token())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/dispatch/pilot/hall?page=bad").header("Authorization", "Bearer " + p.token())).andExpect(status().isBadRequest());
    }
}

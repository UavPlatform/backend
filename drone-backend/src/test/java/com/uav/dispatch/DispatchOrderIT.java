package com.uav.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.mapper.WaybillAssessmentRepository;
import com.uav.server.config.DispatchPaymentConfig;
import com.uav.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(DispatchTestData.AssessmentFixture.class)
@TestPropertySource(properties = "wechat.pay.mock-enabled=true")
class DispatchOrderIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired WaybillAssessmentRepository assessments;
    @Autowired DispatchPaymentConfig config;

    private String assess(String token, Map<String, String> fields) throws Exception {
        String body = mockMvc.perform(post("/dispatch/assessments").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(DispatchTestData.waybill(fields))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("assessmentId").asText();
    }
    private String createBody(String assessment, String key, Map<String, String> fields) throws Exception {
        return json.writeValueAsString(Map.of("assessmentId", assessment, "requestKey", key,
                "declarationAccepted", true, "waybill", DispatchTestData.waybill(fields)));
    }
    @Test void fullSimulationHasServerSnapshotIndependentFundsAndIdempotentRefund() throws Exception {
        var owner = accounts().registerUser();
        var fields = DispatchTestData.fields();
        String body = createBody(assess(owner.token(), fields), UUID.randomUUID().toString(), fields);
        String response = mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stage").value("AWAITING_DEPOSIT"))
                .andExpect(jsonPath("$.data.fundsStatus").value("UNPAID"))
                .andExpect(jsonPath("$.data.paymentMode").value("SIMULATED"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("data").path("orderId").asText();
        for (int i = 0; i < 2; i++) mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.data.orderId").value(id));
        var original = config.getDepositAmount();
        try {
            config.setDepositAmount(new java.math.BigDecimal("999.00"));
            for (int i = 0; i < 2; i++) mockMvc.perform(post("/dispatch/orders/" + id + "/simulate-payment")
                    .header("Authorization", "Bearer " + owner.token())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stage").value("WAITING_ACCEPTANCE"))
                    .andExpect(jsonPath("$.data.depositAmount").value(original.doubleValue()))
                    .andExpect(jsonPath("$.data.paymentReference").value("sim-pay-" + id));
        } finally { config.setDepositAmount(original); }
        for (int i = 0; i < 2; i++) mockMvc.perform(post("/dispatch/orders/" + id + "/cancel")
                .header("Authorization", "Bearer " + owner.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("CANCELLED"))
                .andExpect(jsonPath("$.data.fundsStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.data.refundReference").value("sim-refund-" + id));
        mockMvc.perform(post("/dispatch/orders/" + id + "/simulate-payment").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/dispatch/orders").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }
    @Test void prohibitedChangedExpiredAndForeignAssessmentsCannotCreateOrders() throws Exception {
        var owner = accounts().registerUser();
        var other = accounts().registerUser();
        var f = DispatchTestData.fields();
        String id = assess(owner.token(), f);
        String body = createBody(id, UUID.randomUUID().toString(), f);
        mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + other.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        f.put("weightMin", "3");
        mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(createBody(id, UUID.randomUUID().toString(), f)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ASSESSMENT_INVALID"));
        var a = assessments.findById(id).orElseThrow();
        a.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assessments.saveAndFlush(a);
        mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        f.put("category", "液体");
        String prohibited = assess(owner.token(), f);
        mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(createBody(prohibited, UUID.randomUUID().toString(), f)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("AIRSPACE_PROHIBITED"));
    }
    @Test void orderOwnershipAndUnpaidCancellationAreEnforced() throws Exception {
        var owner = accounts().registerUser();
        var other = accounts().registerUser();
        var f = DispatchTestData.fields();
        String body = createBody(assess(owner.token(), f), UUID.randomUUID().toString(), f);
        var result = mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).path("data").path("orderId").asText();
        for (String action : new String[]{"simulate-payment", "cancel"}) mockMvc.perform(post("/dispatch/orders/" + id + "/" + action)
                .header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
        mockMvc.perform(get("/dispatch/orders/" + id).header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
        mockMvc.perform(post("/dispatch/orders/" + id + "/cancel").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fundsStatus").value("UNPAID"))
                .andExpect(jsonPath("$.data.refundReference").isEmpty());
        var changed = DispatchTestData.fields(); changed.put("count", "2");
        String key = json.readTree(body).path("requestKey").asText();
        mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(createBody("irrelevant", key, changed)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }
    @Test void invalidFieldsDeclarationsAndRolesAreRejected() throws Exception {
        var owner = accounts().registerUser();
        var rider = accounts().registerRider();
        var f = DispatchTestData.fields();
        String assessment = assess(owner.token(), f);
        String body = createBody(assessment, UUID.randomUUID().toString(), f);
        mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + rider.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"declarationAccepted\":true", "\"declarationAccepted\":false")))
                .andExpect(status().isBadRequest());
        f.put("totalAmount", "0.01");
        mockMvc.perform(post("/dispatch/assessments").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(DispatchTestData.waybill(f))))
                .andExpect(status().isBadRequest());
        assertThat(assessments.findById(assessment)).isPresent();
    }

    @Test void staleUnpaidOrderCanRefreshAssessmentWithoutDuplicateOrder() throws Exception {
        var owner = accounts().registerUser();
        var f = DispatchTestData.fields();
        String firstAssessment = assess(owner.token(), f);
        String key = UUID.randomUUID().toString();
        String body = createBody(firstAssessment, key, f);
        var response = mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("data").path("orderId").asText();
        var expired = assessments.findById(firstAssessment).orElseThrow();
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1)); assessments.saveAndFlush(expired);
        mockMvc.perform(post("/dispatch/orders/" + id + "/simulate-payment").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ASSESSMENT_INVALID"));
        String fresh = assess(owner.token(), f);
        mockMvc.perform(post("/dispatch/orders").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(createBody(fresh, key, f)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderId").value(id));
        mockMvc.perform(post("/dispatch/orders/" + id + "/simulate-payment").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stage").value("WAITING_ACCEPTANCE"));
    }
}

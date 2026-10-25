package com.uav.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {"dispatch.payment.deposit-amount=125.50", "dispatch.payment.pilot-service-fee=180.50"})
class DispatchDraftIT extends IntegrationTestBase {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingAssessmentProviderBlocksOrderingWithoutInventedRiskData() throws Exception {
        var owner = accounts().registerUser();
        mockMvc.perform(get("/dispatch/readiness").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderingEnabled").value(false))
                .andExpect(jsonPath("$.data.paymentMode").value("SIMULATED"))
                .andExpect(jsonPath("$.data.blockers[0]").isNotEmpty());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dispatch/assessments")
                .header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(DispatchTestData.waybill(DispatchTestData.fields()))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.errorCode").value("DISPATCH_UNAVAILABLE"));
    }

    private String body(Long revision) throws Exception {
        var request = new java.util.LinkedHashMap<String, Object>();
        request.put("schemaVersion", 1);
        request.put("step", 1);
        request.put("fields", Map.of("category", "普通", "weightMin", "1."));
        if (revision != null) request.put("expectedRevision", revision);
        return mapper.writeValueAsString(request);
    }

    @Test
    void authenticatedConfigurationUsesServerValuesWithoutInventingTotal() throws Exception {
        var owner = accounts().registerUser();
        mockMvc.perform(get("/dispatch/config").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.depositAmount").value(125.50))
                .andExpect(jsonPath("$.data.pilotServiceFee").value(180.50))
                .andExpect(jsonPath("$.data.orderingEnabled").value(false))
                .andExpect(jsonPath("$.data.totalAmount").doesNotExist());
    }

    @Test
    void draftPersistsWithRevisionAndIsIsolatedBetweenUsers() throws Exception {
        var a = accounts().registerUser();
        var b = accounts().registerUser();
        mockMvc.perform(put("/dispatch/waybill-draft").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(0));
        mockMvc.perform(get("/dispatch/waybill-draft").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fields.weightMin").value("1."));
        mockMvc.perform(get("/dispatch/waybill-draft").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(put("/dispatch/waybill-draft").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(0L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        mockMvc.perform(put("/dispatch/waybill-draft").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(0L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WAYBILL_DRAFT_CONFLICT"));
    }

    @Test
    void authenticationAndShipperRoleAreRequired() throws Exception {
        mockMvc.perform(get("/dispatch/config")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/dispatch/waybill-draft").contentType(MediaType.APPLICATION_JSON)
                .content(body(null))).andExpect(status().isUnauthorized());
        var rider = accounts().registerRider();
        mockMvc.perform(get("/dispatch/waybill-draft").header("Authorization", "Bearer " + rider.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/dispatch/waybill-draft").header("Authorization", "Bearer " + rider.token())
                .contentType(MediaType.APPLICATION_JSON).content(body(null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidEnvelopeAndClientControlledPriceAreRejected() throws Exception {
        var owner = accounts().registerUser();
        for (String request : new String[]{"{\"schemaVersion\":1,\"step\":9,\"fields\":{}}",
                "{\"schemaVersion\":1,\"step\":0,\"fields\":{\"totalAmount\":\"1\"}}"}) {
            mockMvc.perform(put("/dispatch/waybill-draft").header("Authorization", "Bearer " + owner.token())
                    .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isBadRequest());
        }
    }
}

package com.uav.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.flight.event.EventPacketSamples;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chain discovery must surface a chain recorded by another terminal, because chain identity includes
 * recording_source_id and a reinstall mints a new one. H2 proves wiring and the permission boundary; durability of the
 * underlying chain rows is covered by the MySQL E2E.
 */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventChainLookupIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired private JdbcTemplate jdbc;

    @Test
    void reportsAChainRecordedByAnotherTerminalWithItsContiguousTip() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        String orderId = prepare(owner, pilot);

        String originalSource = UUID.randomUUID().toString();
        var identity = identity(orderId, originalSource, "FC30SN1");
        upload(pilot, orderId, EventPacketSamples.first(identity, UUID.randomUUID().toString(),
                List.of(positionEvent())));

        String reinstallSource = UUID.randomUUID().toString();
        var body = chainLookup(pilot, orderId, "FC30SN1", reinstallSource);
        assertThat(body.path("success").asBoolean()).isTrue();
        var chains = body.path("data");
        assertThat(chains).hasSize(1);
        var chain = chains.get(0);
        assertThat(chain.path("recordingSourceId").asText()).isEqualTo(originalSource);
        // The reinstalled terminal must see that this chain belongs to another recording source.
        assertThat(chain.path("terminalMismatch").asBoolean()).isTrue();
        assertThat(chain.path("contiguousHeight").asLong()).isEqualTo(1);
        assertThat(chain.path("contiguousHash").asText()).isNotBlank();
        assertThat(chain.path("integrity").asText()).isEqualTo("OPEN");
    }

    @Test
    void reportsNoMismatchWhenTheCallingTerminalOwnsTheChain() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        String orderId = prepare(owner, pilot);

        String source = UUID.randomUUID().toString();
        var identity = identity(orderId, source, "FC30SN1");
        upload(pilot, orderId, EventPacketSamples.first(identity, UUID.randomUUID().toString(),
                List.of(positionEvent())));

        var chains = chainLookup(pilot, orderId, "FC30SN1", source).path("data");
        assertThat(chains).hasSize(1);
        assertThat(chains.get(0).path("terminalMismatch").asBoolean()).isFalse();
    }

    @Test
    void aDifferentAirframeDoesNotLeakIntoTheResult() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        String orderId = prepare(owner, pilot);

        var identity = identity(orderId, UUID.randomUUID().toString(), "FC30SN1");
        upload(pilot, orderId, EventPacketSamples.first(identity, UUID.randomUUID().toString(),
                List.of(positionEvent())));

        // Same order, different serial: the binding key must not match.
        assertThat(chainLookup(pilot, orderId, "OTHER-SN", UUID.randomUUID().toString()).path("data")).isEmpty();
    }

    @Test
    void anotherPilotCannotEnumerateTheChains() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("evt"), null);
        var outsider = accounts().registerRider(UniqueNames.userName("evt"), null);
        String orderId = prepare(owner, pilot);

        var identity = identity(orderId, UUID.randomUUID().toString(), "FC30SN1");
        upload(pilot, orderId, EventPacketSamples.first(identity, UUID.randomUUID().toString(),
                List.of(positionEvent())));

        mockMvc.perform(get("/dispatch/pilot/orders/" + orderId + "/event-chains")
                        .param("serialNumber", "FC30SN1").param("serialSource", "FLIGHT_CONTROLLER")
                        .param("modelCode", "FC30").param("recordingSourceId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + outsider.token()))
                .andExpect(status().isForbidden());
    }

    private com.fasterxml.jackson.databind.JsonNode chainLookup(
            Account pilot, String orderId, String serialNumber, String recordingSourceId) throws Exception {
        var response = mockMvc.perform(get("/dispatch/pilot/orders/" + orderId + "/event-chains")
                        .param("serialNumber", serialNumber).param("serialSource", "FLIGHT_CONTROLLER")
                        .param("modelCode", "FC30").param("recordingSourceId", recordingSourceId)
                        .header("Authorization", "Bearer " + pilot.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private void upload(Account pilot, String orderId, byte[] raw) throws Exception {
        mockMvc.perform(post("/dispatch/pilot/orders/" + orderId + "/event-packets")
                        .header("Authorization", "Bearer " + pilot.token())
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk());
    }

    private EventPacketSamples.Identity identity(String orderId, String source, String serial) {
        long version = jdbc.queryForObject("SELECT assignment_version FROM dispatch_order WHERE id = ?", Long.class, orderId);
        return new EventPacketSamples.Identity(orderId, version, source, UUID.randomUUID().toString(),
                serial, "FLIGHT_CONTROLLER", "FC30");
    }

    private EventPacketSamples.Ev positionEvent() {
        return new EventPacketSamples.Ev(UUID.randomUUID().toString(), System.currentTimeMillis(),
                "POSITION", EventPacketSamples.position(1, 2));
    }

    private String prepare(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        String assessment = send(owner, "/dispatch/assessments", waybill).path("assessmentId").asText();
        String id = send(owner, "/dispatch/orders", Map.of("assessmentId", assessment, "requestKey", UUID.randomUUID().toString(),
                "declarationAccepted", true, "waybill", waybill)).path("orderId").asText();
        send(owner, "/dispatch/orders/" + id + "/simulate-payment", Map.of());
        var card = json.readTree(mockMvc.perform(get("/dispatch/pilot/hall")
                .header("Authorization", "Bearer " + pilot.token())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        com.fasterxml.jackson.databind.JsonNode offer = null;
        for (var item : card.path("data").path("items")) if (id.equals(item.path("orderId").asText())) offer = item;
        assertThat(offer).isNotNull();
        send(pilot, "/dispatch/pilot/orders/" + id + "/claim", Map.of("offerRevision", offer.path("offerRevision").asLong()));
        return id;
    }

    private com.fasterxml.jackson.databind.JsonNode send(Account actor, String path, Object body) throws Exception {
        var response = mockMvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }
}

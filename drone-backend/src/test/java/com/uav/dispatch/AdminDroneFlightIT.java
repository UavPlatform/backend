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

/** REQ-SUP-GOV-005 × REQ-PILOT-EXEC-007: a djifly-reported serial in any case/padding still matches the registered drone. */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminDroneFlightIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode send(String token, String path, Object body, int status) throws Exception {
        var r = mockMvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().is(status)).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("data");
    }
    private JsonNode read(String token, String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String arrivedOrder(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = send(owner.token(), "/dispatch/assessments", waybill, 200);
        String id = send(owner.token(), "/dispatch/orders", Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill), 200).path("orderId").asText();
        send(owner.token(), "/dispatch/orders/" + id + "/simulate-payment", Map.of(), 200);
        JsonNode card = null;
        for (var item : read(pilot.token(), "/dispatch/pilot/hall").path("items")) if (id.equals(item.path("orderId").asText())) card = item;
        assertThat(card).isNotNull();
        send(pilot.token(), "/dispatch/pilot/orders/" + id + "/claim", Map.of("offerRevision", card.path("offerRevision").asLong()), 200);
        send(pilot.token(), "/dispatch/pilot/orders/" + id + "/arrive", Map.of("assignmentVersion", 1), 200);
        return id;
    }

    @Test void lowercasePaddedDjiflySerialMatchesRegisteredDrone() throws Exception {
        var admin = accounts().adminLogin(); var owner = accounts().registerUser();
        var pilot = accounts().registerRider(UniqueNames.userName("adrflight"), null);
        String serial = UniqueNames.djiId().toUpperCase(Locale.ROOT);
        String droneId = send(admin.token(), "/admin/drones", Map.of("aircraftModelId", fixtures.defaultAircraftModelId(), "serialNumber", serial,
                "airportName", "测试机场", "latitude", 28.2, "longitude", 113.0), 200).path("droneId").asText();
        assertThat(read(admin.token(), "/admin/drones/" + droneId).path("latestOrder").isNull()).isTrue();

        String orderId = arrivedOrder(owner, pilot);
        var c = send(pilot.token(), "/dispatch/pilot/orders/" + orderId + "/flight-connections", Map.of("assignmentVersion", 1,
                "requestKey", UUID.randomUUID().toString(), "serialNumber", "  " + serial.toLowerCase(Locale.ROOT) + "  ",
                "modelCode", "FC30", "modelName", "DJI FlyCart 30"), 200);
        assertThat(c.path("serialNumber").asText()).isEqualTo(serial);

        var row = read(admin.token(), "/admin/drones/" + droneId);
        assertThat(row.path("executing").asBoolean()).isTrue();
        assertThat(row.path("latestOrder").path("orderId").asText()).isEqualTo(orderId);
        assertThat(read(admin.token(), "/admin/dispatch/orders/" + orderId).path("order").path("executedDrone").path("serialNumber").asText()).isEqualTo(serial);
    }
}

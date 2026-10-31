package com.uav.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.equipment.mapper.EquipmentNotificationRepository;
import com.uav.equipment.pojo.entity.EquipmentNotification;
import com.uav.equipment.service.EquipmentNotificationService;
import com.uav.server.exception.BusinessException;
import com.uav.support.IntegrationTestBase;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Same numeric recipient_id must not leak across ADMIN / PILOT audience. */
class EquipmentNotificationIT extends IntegrationTestBase {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired EquipmentNotificationRepository notices;
    @Autowired EquipmentNotificationService service;

    private EquipmentNotification row(Long recipient, String audience, String kind, String borrowId) {
        var n = new EquipmentNotification();
        n.setId(UUID.randomUUID().toString());
        n.setRecipientId(recipient);
        n.setAudience(audience);
        n.setEventKey(borrowId + ":" + kind);
        n.setKind(kind);
        n.setTitle(kind);
        n.setBody(kind);
        n.setBorrowId(null); // synthetic rows; FK optional
        n.setCreatedAt(LocalDateTime.now());
        return notices.saveAndFlush(n);
    }

    @Test void listAndReadAreIsolatedByAudienceWhenIdsCollide() {
        Long shared = 42L;
        String borrow = UUID.randomUUID().toString();
        var adminRow = row(shared, EquipmentNotificationService.AUDIENCE_ADMIN,
                EquipmentNotificationService.KIND_PENDING, borrow);
        var pilotRow = row(shared, EquipmentNotificationService.AUDIENCE_PILOT,
                EquipmentNotificationService.KIND_APPROVED, borrow);

        var adminInbox = service.list(shared, 2, 0);
        assertThat(adminInbox.items()).extracting(i -> i.notificationId()).containsExactly(adminRow.getId());
        assertThat(adminInbox.items()).allMatch(i -> "ADMIN".equals(i.audience()));
        assertThat(adminInbox.unreadCount()).isEqualTo(1);

        var pilotInbox = service.list(shared, 1, 0);
        assertThat(pilotInbox.items()).extracting(i -> i.notificationId()).containsExactly(pilotRow.getId());
        assertThat(pilotInbox.items()).allMatch(i -> "PILOT".equals(i.audience()));
        assertThat(pilotInbox.unreadCount()).isEqualTo(1);

        assertThatThrownBy(() -> service.read(shared, 1, adminRow.getId()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.read(shared, 2, pilotRow.getId()))
                .isInstanceOf(BusinessException.class);

        service.read(shared, 2, adminRow.getId());
        assertThat(service.list(shared, 2, 0).unreadCount()).isZero();
        assertThat(service.list(shared, 1, 0).unreadCount()).isEqualTo(1);
    }

    @Test void httpInboxOnlyReturnsOwnAudience() throws Exception {
        var admin = accounts().adminLogin();
        var pilot = accounts().registerRider(UniqueNames.userName("noticollide"), null);
        Long modelId = fixtures.defaultAircraftModelId();
        String droneId = mockMvc.perform(post("/admin/drones").header("Authorization", admin.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("aircraftModelId", modelId, "serialNumber", UniqueNames.djiId()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        droneId = json.readTree(droneId).path("data").path("droneId").asText();
        String borrowId = mockMvc.perform(post("/equipment/pilot/borrows").header("Authorization", "Bearer " + pilot.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("aircraftModelId", modelId, "requestKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        borrowId = json.readTree(borrowId).path("data").path("borrowId").asText();
        mockMvc.perform(post("/admin/borrow-applications/" + borrowId + "/approve")
                        .header("Authorization", admin.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("droneId", droneId))))
                .andExpect(status().isOk());

        // Force a colliding pilot-audience row under the admin's numeric id.
        row(admin.id(), EquipmentNotificationService.AUDIENCE_PILOT,
                EquipmentNotificationService.KIND_APPROVED, UUID.randomUUID().toString());

        JsonNode adminItems = json.readTree(mockMvc.perform(get("/equipment/notifications")
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("items");
        assertThat(adminItems).isNotEmpty();
        for (JsonNode item : adminItems) assertThat(item.path("audience").asText()).isEqualTo("ADMIN");

        JsonNode pilotItems = json.readTree(mockMvc.perform(get("/equipment/notifications")
                        .header("Authorization", "Bearer " + pilot.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("items");
        assertThat(pilotItems).isNotEmpty();
        for (JsonNode item : pilotItems) assertThat(item.path("audience").asText()).isEqualTo("PILOT");
    }
}

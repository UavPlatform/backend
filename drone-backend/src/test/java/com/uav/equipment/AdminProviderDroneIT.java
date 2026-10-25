package com.uav.equipment;

import com.uav.equipment.mapper.EquipmentProviderRepository;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.equipment.pojo.entity.EquipmentProvider;
import com.uav.equipment.pojo.entity.ProviderDrone;
import com.uav.support.IntegrationTestBase;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理端服务商无人机/机场只读列表（ADR-0008）：仅管理员；服务商、机场过滤；参数校验。 */
class AdminProviderDroneIT extends IntegrationTestBase {
    @Autowired EquipmentProviderRepository providers;
    @Autowired ProviderDroneRepository drones;

    private EquipmentProvider provider(String name) {
        EquipmentProvider p = new EquipmentProvider();
        p.setUsername(UniqueNames.userName("prov"));
        p.setPasswordHash("x");
        p.setDisplayName(name);
        p.setCreatedAt(LocalDateTime.now());
        return providers.saveAndFlush(p);
    }

    private ProviderDrone drone(Long provider, String airport, LocalDateTime created) {
        ProviderDrone d = new ProviderDrone();
        d.setId(UUID.randomUUID().toString());
        d.setProviderId(provider);
        d.setAircraftModelId(fixtures.defaultAircraftModelId());
        d.setSerialNumber(UniqueNames.djiId());
        d.setAirportName(airport);
        d.setLatitude(new BigDecimal("28.200000"));
        d.setLongitude(new BigDecimal("113.000000"));
        d.setCreatedAt(created);
        return drones.saveAndFlush(d);
    }

    @Test void adminListsWithProviderAndAirportFilters() throws Exception {
        var a = provider("甲服务商");
        var b = provider("乙服务商");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime base = LocalDateTime.of(2031, 3, 1, 8, 0);
        ProviderDrone older = drone(a.getId(), "岳麓机场" + tag, base);
        ProviderDrone newer = drone(a.getId(), "橘子洲机场" + tag, base.plusHours(1));
        ProviderDrone other = drone(b.getId(), "岳麓机场" + tag, base.plusHours(2));
        var admin = accounts().adminLogin();

        mockMvc.perform(get("/admin/provider/drones").header("Authorization", admin.authorization())
                        .param("providerId", a.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].droneId").value(newer.getId()))
                .andExpect(jsonPath("$.data.content[0].provider.providerId").value(a.getId()))
                .andExpect(jsonPath("$.data.content[0].provider.displayName").value("甲服务商"))
                .andExpect(jsonPath("$.data.content[0].model.aircraftModelId").value(fixtures.defaultAircraftModelId()))
                .andExpect(jsonPath("$.data.content[0].serialNumber").value(newer.getSerialNumber()))
                .andExpect(jsonPath("$.data.content[0].latitude").value(28.2))
                .andExpect(jsonPath("$.data.content[0].longitude").value(113.0))
                .andExpect(jsonPath("$.data.content[1].droneId").value(older.getId()));

        mockMvc.perform(get("/admin/provider/drones").header("Authorization", admin.authorization())
                        .param("airportName", " 岳麓机场" + tag + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].droneId").value(other.getId()))
                .andExpect(jsonPath("$.data.content[0].provider.displayName").value("乙服务商"));

        mockMvc.perform(get("/admin/provider/drones").header("Authorization", admin.authorization())
                        .param("providerId", a.getId().toString()).param("airportName", tag).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(1));

        mockMvc.perform(get("/admin/provider/drones").header("Authorization", admin.authorization())
                        .param("size", "500"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(100));
        mockMvc.perform(get("/admin/provider/drones").header("Authorization", admin.authorization())
                        .param("airportName", "100%_" + tag))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test void badParamsAreRejected() throws Exception {
        var admin = accounts().adminLogin();
        String[][] bad = {{"providerId", "0"}, {"providerId", "-3"}, {"providerId", "abc"}, {"page", "-1"},
                {"size", "0"}, {"airportName", "x".repeat(101)}};
        for (String[] p : bad) {
            mockMvc.perform(get("/admin/provider/drones").header("Authorization", admin.authorization()).param(p[0], p[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void nonAdminsAreForbiddenAndAnonymousUnauthorized() throws Exception {
        var user = accounts().registerUser();
        var pilot = accounts().registerRider();
        for (var acc : new com.uav.support.TestAccounts.Account[]{user, pilot}) {
            mockMvc.perform(get("/admin/provider/drones").header("Authorization", acc.authorization()))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/provider/drones")).andExpect(status().isUnauthorized());
    }

    @Test void providerRoleIsForbidden() throws Exception {
        var admin = accounts().adminLogin();
        String name = UniqueNames.userName("provider");
        mockMvc.perform(post("/equipment/admin/providers").header("Authorization", admin.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"equipment-test-pass\",\"displayName\":\"p\"}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/equipment/provider/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"equipment-test-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("token").asText();
        org.assertj.core.api.Assertions.assertThat(com.uav.support.TestAccounts.claims(token).path("role").asInt()).isEqualTo(3);
        mockMvc.perform(get("/admin/provider/drones").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}

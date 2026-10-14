package com.uav.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 飞手绑机路径应幂等补齐 {@code uav} 设备档案（#22），且不得覆盖 App 侧已注册的运行时字段。
 */
class BindDroneUavArchiveIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private UavRepository uavRepository;

    @Test
    @DisplayName("bindDrone：无档案时创建 uav 行（离线、可用）")
    void bindDroneCreatesUavArchiveWhenMissing() {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("bd"), null);
        String djiId = UniqueNames.djiId();
        assertThat(uavRepository.findByDjiId(djiId)).isEmpty();

        fixtures.bindDrone(rider.id(), djiId);

        Uav uav = uavRepository.findByDjiId(djiId).orElseThrow();
        assertThat(uav.getOnlineStatus()).isEqualTo('0');
        assertThat(uav.getIsAvailable()).isEqualTo('1');
        assertThat(uav.getUavName()).isEqualTo(djiId);
    }

    @Test
    @DisplayName("bindDrone：已有档案时不覆盖 online/is_available")
    void bindDroneDoesNotOverwriteExistingArchive() {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("bd2"), null);
        String djiId = UniqueNames.djiId();
        Uav existing = new Uav();
        existing.setDjiId(djiId);
        existing.setUavName("pre-registered");
        existing.setOnlineStatus('1');
        existing.setIsAvailable('0');
        existing.setControllerModel("Legacy");
        uavRepository.save(existing);

        fixtures.bindDrone(rider.id(), djiId);

        Uav uav = uavRepository.findByDjiId(djiId).orElseThrow();
        assertThat(uav.getUavName()).isEqualTo("pre-registered");
        assertThat(uav.getOnlineStatus()).isEqualTo('1');
        assertThat(uav.getIsAvailable()).isEqualTo('0');
        assertThat(uav.getControllerModel()).isEqualTo("Legacy");
    }

    @Test
    @DisplayName("飞手注册绑机：监管统计 totalUavs 与绑定数一致")
    void adminStatisticsCountsArchivesFromBindPath() throws Exception {
        accounts().registerRider();
        accounts().registerRider();

        TestAccounts.AdminAccount admin = accounts().adminLogin();
        String body = mockMvc.perform(get("/admin/uav/statistics")
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = MAPPER.readTree(body).path("data");
        assertThat(data.path("totalUavs").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(data.path("offlineUavs").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(data.path("availableUavs").asInt()).isGreaterThanOrEqualTo(2);
    }
}

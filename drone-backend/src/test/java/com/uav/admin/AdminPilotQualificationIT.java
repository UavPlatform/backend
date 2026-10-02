package com.uav.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 监管端飞手详情回显证照与驾驶资质（REQ-FRONTEND-001「飞手详情」/ ADR-0004）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}；R3：飞手与管理员身份取自真实注册/登录接口，
 * 飞手资料经真实 {@code PATCH /rider/info} 写入，而非直连仓储。
 */
class AdminPilotQualificationIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID_NUMBER = "11010519491231002X";

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Test
    @DisplayName("飞手详情：脱敏身份证号、证件有效期与资质（机型 + 等级中文名），不含身份证号原值")
    void pilotDetailExposesMaskedIdAndQualifications() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("aq"), null);
        long m350 = aircraftModelRepository.findByModelCode("M350RTK").orElseThrow().getId();
        LocalDate expiry = LocalDate.now().plusYears(3);

        mockMvc.perform(patch("/rider/info")
                        .header("Authorization", rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "idNumber", ID_NUMBER,
                                "idExpiryDate", expiry.toString(),
                                "qualifications", List.of(Map.of("aircraftModelId", m350, "licenseGrade", "VLOS"))))))
                .andExpect(status().isOk());

        TestAccounts.AdminAccount admin = accounts().adminLogin();
        String raw = mockMvc.perform(get("/admin/pilots/" + rider.id()).header("Authorization", admin.authorization()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(raw).as("监管端同样只回显脱敏值").doesNotContain(ID_NUMBER);

        JsonNode data = MAPPER.readTree(raw).path("data");
        assertThat(data.path("idNumberMasked").asText()).isEqualTo("110***********002X");
        assertThat(data.path("idExpiryDate").asText()).isEqualTo(expiry.toString());
        assertThat(data.path("idExpired").asBoolean()).isFalse();
        JsonNode quals = data.path("qualifications");
        assertThat(quals.size()).isEqualTo(1);
        assertThat(quals.get(0).path("aircraftModelId").asLong()).isEqualTo(m350);
        assertThat(quals.get(0).path("modelCode").asText()).isEqualTo("M350RTK");
        assertThat(quals.get(0).path("licenseGrade").asText()).isEqualTo("VLOS");
        assertThat(quals.get(0).path("licenseGradeLabel").asText()).isEqualTo("视距内");
        // 既有字段不回归
        assertThat(data.path("userId").asLong()).isEqualTo(rider.id());
        assertThat(data.path("drones").isArray()).isTrue();
        assertThat(data.path("orders").isArray()).isTrue();
    }

    @Test
    @DisplayName("飞手从未保存资料：证件字段为 null、idExpired=false、资质为空数组")
    void pilotDetailWithoutProfile() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("aq"), null);
        TestAccounts.AdminAccount admin = accounts().adminLogin();

        String raw = mockMvc.perform(get("/admin/pilots/" + rider.id()).header("Authorization", admin.authorization()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = MAPPER.readTree(raw).path("data");
        assertThat(data.path("idNumberMasked").isNull()).isTrue();
        assertThat(data.path("idExpiryDate").isNull()).isTrue();
        assertThat(data.path("idExpired").asBoolean()).isFalse();
        assertThat(data.path("qualifications").isArray()).isTrue();
        assertThat(data.path("qualifications").isEmpty()).isTrue();
    }
}

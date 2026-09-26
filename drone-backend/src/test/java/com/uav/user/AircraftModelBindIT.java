package com.uav.user;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.user.pojo.entity.User;
import com.uav.user.service.RiderUavService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 飞手设备机型映射：绑机/解绑校验与吊运应征门禁（TASK-BACKEND-001 / REQ-BACKEND-001 完成条件）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + MockMvc + {@code @Transactional} 回滚）；
 * R3：真实注册链路取 token（{@code accounts()}），不自签 JWT；R6：AssertJ + 项目统一 MockMvc 断言；
 * R7：造数走 {@link TestFixtures} 生产绑定路径与 {@link UniqueNames} 唯一命名。
 *
 * <p>核心完成条件：未映射机型的设备不能用于吊运应征——错误码 {@code AIRCRAFT_MODEL_REQUIRED}
 * （占位门禁 {@code RiderUavService#requireTransportDevice}，由 TASK-BACKEND-003 应征链路接线）。
 */
class AircraftModelBindIT extends IntegrationTestBase {

    @Autowired
    private RiderUavService riderUavService;

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    private long modelId(String modelCode) {
        return aircraftModelRepository.findByModelCode(modelCode)
                .orElseThrow(() -> new AssertionError("缺少机型种子: " + modelCode))
                .getId();
    }

    @Test
    @DisplayName("绑机提交 djiId + aircraftModelId：绑定记录含机型 ID，列表可回读")
    void bindStoresAircraftModelId() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rider"), null);
        String dji = UniqueNames.djiId();
        long fc30 = modelId("FC30");

        mockMvc.perform(post("/rider/drone/bind")
                        .param("djiId", dji)
                        .param("aircraftModelId", String.valueOf(fc30))
                        .header("Authorization", rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/rider/drone/list")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].djiId").value(dji))
                .andExpect(jsonPath("$.data[0].aircraftModelId").value((int) fc30));
    }

    @Test
    @DisplayName("绑机拒绝：机型不存在或已停用 → AIRCRAFT_MODEL_NOT_FOUND")
    void bindRejectsUnknownOrDisabledModel() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rider"), null);

        mockMvc.perform(post("/rider/drone/bind")
                        .param("djiId", UniqueNames.djiId())
                        .param("aircraftModelId", "999999999")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND.getCode()));

        AircraftModel disabled = new AircraftModel();
        disabled.setModelCode(UniqueNames.unique("mdl-disabled"));
        disabled.setDisplayName("停用机型");
        disabled.setMaxPayloadKg(new BigDecimal("1.00"));
        disabled.setCoefficient(new BigDecimal("1.000"));
        disabled.setEnabled(false);
        disabled.setTransportEnabled(true);
        aircraftModelRepository.save(disabled);

        mockMvc.perform(post("/rider/drone/bind")
                        .param("djiId", UniqueNames.djiId())
                        .param("aircraftModelId", String.valueOf(disabled.getId()))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("解绑：提交机型须与绑定记录一致，一致后解绑成功")
    void unbindRequiresMatchingModel() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rider"), null);
        String dji = UniqueNames.djiId();
        long fc30 = modelId("FC30");
        long m350 = modelId("M350RTK");

        mockMvc.perform(post("/rider/drone/bind")
                        .param("djiId", dji)
                        .param("aircraftModelId", String.valueOf(fc30))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/rider/drone/unbind")
                        .param("djiId", dji)
                        .param("aircraftModelId", String.valueOf(m350))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.AIRCRAFT_MODEL_MISMATCH.getCode()));

        mockMvc.perform(delete("/rider/drone/unbind")
                        .param("djiId", dji)
                        .param("aircraftModelId", String.valueOf(fc30))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/rider/drone/list")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("完成条件：未映射机型的设备不能用于吊运应征 → AIRCRAFT_MODEL_REQUIRED")
    void transportGateRejectsUnmappedDevice() {
        // 注册路径不带机型（与 /rider/register 旧客户端一致）→ 设备未映射
        TestAccounts.Account rider = accounts().registerRider();
        long fc30 = modelId("FC30");

        assertThatThrownBy(() -> riderUavService.requireTransportDevice(rider.id(), fc30))
                .as("未映射机型的设备必须被应征门禁拦截")
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.AIRCRAFT_MODEL_REQUIRED.getCode()));

        // 未指定机型同样拦截
        assertThatThrownBy(() -> riderUavService.requireTransportDevice(rider.id(), null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.AIRCRAFT_MODEL_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("应征门禁：已映射设备 + 可吊运机型 → 返回机型（供计价读取系数）")
    void transportGateAcceptsMappedDevice() {
        User rider = fixtures.rider();
        fixtures.bindDrone(rider); // 生产绑定路径，默认映射种子 FC30

        AircraftModel model = riderUavService.requireTransportDevice(rider.getId(), modelId("FC30"));

        assertThat(model.getModelCode()).isEqualTo("FC30");
        assertThat(model.getCoefficient()).isEqualByComparingTo("1.000");
        assertThat(model.getTransportEnabled()).isTrue();
    }

    @Test
    @DisplayName("应征门禁：设备已映射其它机型 → AIRCRAFT_MODEL_MISMATCH")
    void transportGateRejectsModelMismatch() {
        User rider = fixtures.rider();
        fixtures.bindDrone(rider); // 映射 FC30

        assertThatThrownBy(() -> riderUavService.requireTransportDevice(rider.getId(), modelId("M350RTK")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.AIRCRAFT_MODEL_MISMATCH.getCode()));
    }

    @Test
    @DisplayName("应征门禁：不可吊运机型 → AIRCRAFT_MODEL_NOT_TRANSPORTABLE；未绑定设备 → UAV_NOT_FOUND")
    void transportGateRejectsNonTransportModelAndMissingDevice() {
        AircraftModel ground = new AircraftModel();
        ground.setModelCode(UniqueNames.unique("mdl-ground"));
        ground.setDisplayName("不可吊运机型");
        ground.setMaxPayloadKg(new BigDecimal("2.00"));
        ground.setCoefficient(new BigDecimal("1.000"));
        ground.setEnabled(true);
        ground.setTransportEnabled(false);
        aircraftModelRepository.save(ground);

        User riderWithGroundDrone = fixtures.rider();
        fixtures.bindDrone(riderWithGroundDrone.getId(), UniqueNames.djiId(), ground.getId());

        assertThatThrownBy(() ->
                riderUavService.requireTransportDevice(riderWithGroundDrone.getId(), ground.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.AIRCRAFT_MODEL_NOT_TRANSPORTABLE.getCode()));

        TestAccounts.Account noDevice = accounts().registerRider(UniqueNames.userName("rider"), null);
        assertThatThrownBy(() -> riderUavService.requireTransportDevice(noDevice.id(), modelId("FC30")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.UAV_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("注册携带机型：绑定即映射；只给机型不给 djiId → INVALID_PARAM")
    void registerWithModelMapsDevice() {
        String dji = UniqueNames.djiId();
        long fc30 = modelId("FC30");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", UniqueNames.userName("rider"));
        body.put("password", TestAccounts.DEFAULT_PASSWORD);
        body.put("djiId", dji);
        body.put("aircraftModelId", fc30);

        TestAccounts.Response ok = accounts().postJson("/rider/register", body);
        assertThat(ok.success()).as("注册响应=%s", ok.body()).isTrue();
        String token = ok.json().path("data").path("token").asText();
        long userId = TestAccounts.claims(token).path("userId").asLong();

        AircraftModel model = riderUavService.requireTransportDevice(userId, fc30);
        assertThat(model.getModelCode()).isEqualTo("FC30");

        // 机型与设备脱钩：仅 aircraftModelId、无 djiId 的注册应被参数校验拒绝
        Map<String, Object> badBody = new LinkedHashMap<>();
        badBody.put("userName", UniqueNames.userName("rider"));
        badBody.put("password", TestAccounts.DEFAULT_PASSWORD);
        badBody.put("aircraftModelId", fc30);
        TestAccounts.Response bad = accounts().postJson("/rider/register", badBody);
        assertThat(bad.status()).as("仅机型不带 djiId 应 400，响应=%s", bad.body()).isEqualTo(400);
        assertThat(bad.json().path("errorCode").asText()).isEqualTo(ApiErrorCode.INVALID_PARAM.getCode());
    }
}

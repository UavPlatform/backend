package com.uav.rider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.rider.mapper.RiderRepository;
import com.uav.server.enums.ApiErrorCode;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 飞手资料与驾驶资质 {@code GET/PATCH /rider/info}（重写自 PR #14，REQ-APP-001「飞手：资料维护」）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + MockMvc + {@code @Transactional} 回滚）；
 * R3：身份取自真实注册/登录接口；R6：AssertJ + MockMvc；R7：唯一命名走 {@link UniqueNames}。
 *
 * <p>覆盖：空资料、PATCH 局部更新语义、身份证号校验与脱敏（出参从不含原值）、证件有效期校验、
 * 资质按机型集合差异（新增/改等级/删除/清空）、机型门禁、非法请求体 400、角色 401/403。
 */
class RiderInfoApiIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** GB 11643 合法样例号（公开示例）。 */
    private static final String ID_NUMBER = "11010519491231002X";
    private static final String ID_MASKED = "110***********002X";

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Autowired
    private RiderRepository riderRepository;

    @Test
    @DisplayName("GET 未保存过资料：仅用户名，其余为空，资质为空数组")
    void emptyProfileForNewRider() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("ri"), null);

        JsonNode data = okData(mockMvc.perform(get("/rider/info").header("Authorization", rider.authorization())));
        assertThat(data.path("userId").asLong()).isEqualTo(rider.id());
        assertThat(data.path("userName").asText()).isEqualTo(rider.userName());
        assertThat(data.path("idNumberMasked").isNull()).isTrue();
        assertThat(data.path("idExpiryDate").isNull()).isTrue();
        assertThat(data.path("idExpired").asBoolean()).isFalse();
        assertThat(data.path("createTime").isNull()).isTrue();
        assertThat(data.path("qualifications").isArray()).isTrue();
        assertThat(data.path("qualifications").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("PATCH 全量保存：身份证号规范化落库、出参脱敏；资质回显机型与等级中文名；GET 同口径")
    void saveProfileMasksIdNumberAndEchoesQualifications() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("ri"), null);
        long fc30 = modelId("FC30");
        LocalDate expiry = LocalDate.now().plusYears(5);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("age", 30);
        body.put("idNumber", " 11010519491231002x ");
        body.put("idExpiryDate", expiry.toString());
        body.put("location", "广东省深圳市");
        body.put("selfIntroduction", "五年吊运经验");
        body.put("qualifications", List.of(qual(fc30, "BVLOS")));

        String raw = patchInfo(rider, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(raw).as("出参不得包含身份证号原值").doesNotContain(ID_NUMBER).doesNotContain("19491231");
        JsonNode data = MAPPER.readTree(raw).path("data");
        assertThat(data.path("age").asInt()).isEqualTo(30);
        assertThat(data.path("idNumberMasked").asText()).isEqualTo(ID_MASKED);
        assertThat(data.path("idExpiryDate").asText()).isEqualTo(expiry.toString());
        assertThat(data.path("idExpired").asBoolean()).isFalse();
        assertThat(data.path("location").asText()).isEqualTo("广东省深圳市");
        assertThat(data.path("selfIntroduction").asText()).isEqualTo("五年吊运经验");
        assertThat(data.path("createTime").isNull()).isFalse();
        assertThat(data.path("updateTime").isNull()).isFalse();
        JsonNode q = data.path("qualifications");
        assertThat(q.size()).isEqualTo(1);
        assertThat(q.get(0).path("aircraftModelId").asLong()).isEqualTo(fc30);
        assertThat(q.get(0).path("modelCode").asText()).isEqualTo("FC30");
        assertThat(q.get(0).path("modelName").asText()).isEqualTo("DJI FlyCart 30");
        assertThat(q.get(0).path("licenseGrade").asText()).isEqualTo("BVLOS");
        assertThat(q.get(0).path("licenseGradeLabel").asText()).isEqualTo("超视距");

        assertThat(riderRepository.findById(rider.id()).orElseThrow().getIdNumber())
                .as("落库为规范化值（去空白、末位大写 X）").isEqualTo(ID_NUMBER);

        String getRaw = mockMvc.perform(get("/rider/info").header("Authorization", rider.authorization()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(getRaw).doesNotContain(ID_NUMBER);
        assertThat(MAPPER.readTree(getRaw).path("data").path("idNumberMasked").asText()).isEqualTo(ID_MASKED);
    }

    @Test
    @DisplayName("PATCH 局部更新：null 字段与未传 qualifications 均保持原值")
    void patchKeepsUnspecifiedFields() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("ri"), null);
        long fc30 = modelId("FC30");
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("age", 28);
        first.put("idNumber", ID_NUMBER);
        first.put("location", "杭州");
        first.put("qualifications", List.of(qual(fc30, "VLOS")));
        patchInfo(rider, first).andExpect(status().isOk());

        JsonNode data = okData(patchInfo(rider, Map.of("location", "宁波")));
        assertThat(data.path("location").asText()).isEqualTo("宁波");
        assertThat(data.path("age").asInt()).isEqualTo(28);
        assertThat(data.path("idNumberMasked").asText()).isEqualTo(ID_MASKED);
        assertThat(data.path("qualifications").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("资质集合差异：同机型改等级保留原记录、缺失机型删除、新机型新增；[] 清空")
    void qualificationsSyncBySetDiff() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("ri"), null);
        long fc30 = modelId("FC30");
        long m350 = modelId("M350RTK");

        JsonNode first = okData(patchInfo(rider, Map.of("qualifications",
                List.of(qual(fc30, "VLOS"), qual(m350, "BVLOS")))));
        assertThat(first.path("qualifications").size()).isEqualTo(2);
        long fc30QualId = qualByModel(first, fc30).path("id").asLong();

        JsonNode second = okData(patchInfo(rider, Map.of("qualifications", List.of(qual(fc30, "TEACHER")))));
        assertThat(second.path("qualifications").size()).isEqualTo(1);
        JsonNode kept = qualByModel(second, fc30);
        assertThat(kept.path("id").asLong()).as("同机型改等级应原地更新而非删后重建").isEqualTo(fc30QualId);
        assertThat(kept.path("licenseGrade").asText()).isEqualTo("TEACHER");
        assertThat(kept.path("licenseGradeLabel").asText()).isEqualTo("教员");

        JsonNode cleared = okData(patchInfo(rider, Map.of("qualifications", List.of())));
        assertThat(cleared.path("qualifications").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("资质机型门禁：不存在/停用机型 → 400 AIRCRAFT_MODEL_NOT_FOUND；已持有资质的机型停用后原样保留")
    void qualificationRequiresEnabledModel() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("ri"), null);

        patchInfo(rider, Map.of("qualifications", List.of(qual(999_999_999L, "VLOS"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND.getCode()));

        AircraftModel model = new AircraftModel();
        model.setModelCode(UniqueNames.unique("mdl"));
        model.setDisplayName("待停用机型");
        model.setMaxPayloadKg(new BigDecimal("5.00"));
        model.setCoefficient(new BigDecimal("1.000"));
        model.setEnabled(true);
        model.setTransportEnabled(true);
        aircraftModelRepository.save(model);
        patchInfo(rider, Map.of("qualifications", List.of(qual(model.getId(), "VLOS")))).andExpect(status().isOk());

        model.setEnabled(false);
        aircraftModelRepository.save(model);
        // 原样提交已持有资质：保留
        JsonNode kept = okData(patchInfo(rider, Map.of("qualifications", List.of(qual(model.getId(), "VLOS")))));
        assertThat(kept.path("qualifications").size()).isEqualTo(1);
        // 对停用机型改等级：拒绝
        patchInfo(rider, Map.of("qualifications", List.of(qual(model.getId(), "BVLOS"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("参数校验：身份证号/有效期/年龄/重复机型/缺字段/非法枚举与日期 → 400 INVALID_PARAM，且提示不回显身份证号")
    void rejectsInvalidInput() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("ri"), null);
        long fc30 = modelId("FC30");

        String badId = "110105194912310021";
        String body = expectInvalidParam(patchInfo(rider, Map.of("idNumber", badId)));
        assertThat(body).as("错误提示不得回显身份证号").doesNotContain(badId);
        expectInvalidParam(patchInfo(rider, Map.of("idNumber", "   ")));
        expectInvalidParam(patchInfo(rider, Map.of("idExpiryDate", LocalDate.now().minusDays(1).toString())));
        expectInvalidParam(patchInfo(rider, Map.of("age", 15)));
        expectInvalidParam(patchInfo(rider, Map.of("selfIntroduction", "x".repeat(256))));
        expectInvalidParam(patchInfo(rider, Map.of("qualifications", List.of(qual(fc30, "VLOS"), qual(fc30, "BVLOS")))));
        Map<String, Object> missingGrade = new LinkedHashMap<>();
        missingGrade.put("aircraftModelId", fc30);
        expectInvalidParam(patchInfo(rider, Map.of("qualifications", List.of(missingGrade))));
        // 请求体无法解析（非法枚举/日期）：400 而非兜底 500
        expectInvalidParam(patchInfo(rider, Map.of("qualifications", List.of(qual(fc30, "PILOT_KING")))));
        expectInvalidParam(patchInfo(rider, Map.of("idExpiryDate", "2030/01/01")));

        // 今天到期视为有效
        JsonNode ok = okData(patchInfo(rider, Map.of("idExpiryDate", LocalDate.now().toString())));
        assertThat(ok.path("idExpired").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("权限：未登录 401；普通用户与管理员 403（仅飞手本人可访问）")
    void onlyRidersCanAccess() throws Exception {
        mockMvc.perform(get("/rider/info")).andExpect(status().isUnauthorized());

        TestAccounts.Account user = accounts().registerUser();
        mockMvc.perform(get("/rider/info").header("Authorization", user.authorization()))
                .andExpect(status().isForbidden());
        patchInfo(user, Map.of("age", 30)).andExpect(status().isForbidden());

        TestAccounts.AdminAccount admin = accounts().adminLogin();
        mockMvc.perform(get("/rider/info").header("Authorization", admin.authorization()))
                .andExpect(status().isForbidden());
    }

    // ---------- 工具 ----------

    private long modelId(String code) {
        return aircraftModelRepository.findByModelCode(code)
                .orElseThrow(() -> new AssertionError("缺少机型种子: " + code)).getId();
    }

    private static Map<String, Object> qual(long modelId, String grade) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("aircraftModelId", modelId);
        q.put("licenseGrade", grade);
        return q;
    }

    private ResultActions patchInfo(TestAccounts.Account account, Map<String, ?> body) throws Exception {
        return mockMvc.perform(patch("/rider/info")
                .header("Authorization", account.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)));
    }

    private JsonNode okData(ResultActions actions) throws Exception {
        String raw = actions.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(raw).path("data");
    }

    private String expectInvalidParam(ResultActions actions) throws Exception {
        return actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.INVALID_PARAM.getCode()))
                .andReturn().getResponse().getContentAsString();
    }

    private static JsonNode qualByModel(JsonNode data, long modelId) {
        List<JsonNode> hits = new ArrayList<>();
        data.path("qualifications").forEach(q -> {
            if (q.path("aircraftModelId").asLong() == modelId) {
                hits.add(q);
            }
        });
        assertThat(hits).as("资质中应恰有一条 aircraftModelId=%d，实际=%s", modelId, data).hasSize(1);
        return hits.get(0);
    }
}

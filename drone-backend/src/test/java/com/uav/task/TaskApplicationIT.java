package com.uav.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.server.calculator.RoutePriceCalculator;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.CargoCategory;
import com.uav.server.enums.TaskType;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 飞手应征与报价（TASK-BACKEND-003 / REQ-BACKEND-001 / ADR-0003）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + MockMvc + {@code @Transactional} 回滚）；
 * R3：token 由 {@link TestAccounts} 真实注册取得；R6：AssertJ + 项目统一 MockMvc 断言；
 * R7：任务/飞手造数走 {@link com.uav.support.TestFixtures} 与 {@link UniqueNames}。
 *
 * <p>覆盖：应征 → 属主列表含机型与 quotedAmount；同任务不同机型报价不同且可复现；
 * 客户端自定义金额被忽略；未映射机型 → AIRCRAFT_MODEL_REQUIRED；超重 → EXCEEDS_PAYLOAD；
 * 只读放行边界（属主/应征/管理员 200，非属主非应征 403，TASK-BACKEND-007）；
 * V3 迁移落地（R10 双兼容查询）。
 */
class TaskApplicationIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** application.yml 计价配置（平台 SSOT，测试以常量独立复核公式）。 */
    private static final BigDecimal PRICE_PER_METER = new BigDecimal("0.05");
    private static final BigDecimal PRICE_PER_KG = new BigDecimal("2.00");
    private static final BigDecimal CONSTRUCTION_SURCHARGE = new BigDecimal("50.00");

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private DataSource dataSource;

    private long modelId(String modelCode) {
        return aircraftModelRepository.findByModelCode(modelCode)
                .orElseThrow(() -> new AssertionError("缺少机型种子: " + modelCode))
                .getId();
    }

    /** 属主创建吊运任务（2 航点 + 货物字段），返回 taskNum。 */
    private String createTransportTask(TestAccounts.Account owner, String cargoWeightKg) throws Exception {
        TaskDto dto = fixtures.twoWaypointTask(UniqueNames.unique("task"));
        dto.setType(TaskType.TRANSPORT);
        if (cargoWeightKg != null) {
            dto.setCargoWeightKg(new BigDecimal(cargoWeightKg));
        }
        dto.setCargoCategory(CargoCategory.CONSTRUCTION);
        String body = mockMvc.perform(post("/task/create")
                        .header("Authorization", owner.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        String taskNum = MAPPER.readTree(body).path("data").path("taskNum").asText();
        assertThat(taskNum).as("创建任务应返回 taskNum，响应=%s", body).isNotBlank();
        return taskNum;
    }

    /** 注册无设备飞手并绑定一台映射到指定机型的设备（生产绑机路径）。 */
    private TestAccounts.Account riderMappedTo(String modelCode) throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rider"), null);
        mockMvc.perform(post("/rider/drone/bind")
                        .param("djiId", UniqueNames.djiId())
                        .param("aircraftModelId", String.valueOf(modelId(modelCode)))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        return rider;
    }

    /** POST /rider/apply，返回成功响应体（断言 200 由调用方负责）。 */
    private JsonNode applyOk(TestAccounts.Account rider, String taskNum, long aircraftModelId,
                             String extraJsonFields) throws Exception {
        String body = "{\"taskNum\":\"" + taskNum + "\",\"aircraftModelId\":" + aircraftModelId
                + extraJsonFields + "}";
        return MAPPER.readTree(mockMvc.perform(post("/rider/apply")
                        .header("Authorization", rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString());
    }

    /** 应征列表（属主），返回 data 数组。 */
    private JsonNode applications(TestAccounts.Account owner, String taskNum) throws Exception {
        String body = mockMvc.perform(get("/task/" + taskNum + "/applications")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).path("data");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        assertThat(node.has(field)).as("响应缺少字段 %s: %s", field, node).isTrue();
        return new BigDecimal(node.get(field).asText());
    }

    /**
     * 独立复核 ADR-0003 首版公式（只与实现共享 haversine 距离助手，不共享计价器）：
     * {@code quotedAmount = (distance×0.05 + weight×2.00 + 类别附加费) × 机型系数}，2 位 HALF_UP。
     */
    private BigDecimal expectedQuote(String taskNum, String weight, String coefficient) {
        Task task = taskRepository.findByTaskNum(taskNum)
                .orElseThrow(() -> new AssertionError("任务不存在: " + taskNum));
        BigDecimal distance = RoutePriceCalculator.calculateTotalDistance(task.getWaypoints());
        BigDecimal distanceCharge = RoutePriceCalculator.calculatePrice(distance, PRICE_PER_METER);
        BigDecimal weightCharge = PRICE_PER_KG.multiply(new BigDecimal(weight))
                .setScale(2, RoundingMode.HALF_UP);
        return distanceCharge.add(weightCharge).add(CONSTRUCTION_SURCHARGE)
                .multiply(new BigDecimal(coefficient)).setScale(2, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("应征 → 属主列表含飞手/机型/载重/quotedAmount/应征时间/状态")
    void applyThenOwnerListsApplication() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String taskNum = createTransportTask(owner, "2.00");
        TestAccounts.Account rider = riderMappedTo("FC30");

        JsonNode applied = applyOk(rider, taskNum, modelId("FC30"), "");
        JsonNode data = applied.path("data");
        BigDecimal expected = expectedQuote(taskNum, "2.00", "1.000");
        assertThat(decimal(data, "quotedAmount"))
                .as("报价应等于平台公式（距离费+重量费+类别费）× FC30 系数 1.000")
                .isEqualByComparingTo(expected);
        assertThat(data.path("modelCode").asText()).isEqualTo("FC30");
        assertThat(data.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.path("riderId").asLong()).isEqualTo(rider.id());

        JsonNode list = applications(owner, taskNum);
        assertThat(list.size()).isEqualTo(1);
        JsonNode item = list.get(0);
        assertThat(item.path("riderName").asText()).isEqualTo(rider.userName());
        assertThat(item.path("modelCode").asText()).isEqualTo("FC30");
        assertThat(item.path("aircraftModelName").asText()).contains("FlyCart");
        assertThat(decimal(item, "maxPayloadKg")).isEqualByComparingTo("30.00");
        assertThat(decimal(item, "quotedAmount")).isEqualByComparingTo(expected);
        assertThat(item.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(item.path("appliedAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("同任务不同机型报价不同且可复现；重复应征更新原记录并保持同一报价")
    void differentModelsDifferentReproducibleQuotes() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String taskNum = createTransportTask(owner, "2.00");
        TestAccounts.Account fc30Rider = riderMappedTo("FC30");
        TestAccounts.Account m350Rider = riderMappedTo("M350RTK");

        BigDecimal quoteFc30 = decimal(
                applyOk(fc30Rider, taskNum, modelId("FC30"), "").path("data"), "quotedAmount");
        BigDecimal quoteM350 = decimal(
                applyOk(m350Rider, taskNum, modelId("M350RTK"), "").path("data"), "quotedAmount");

        BigDecimal expectedFc30 = expectedQuote(taskNum, "2.00", "1.000");
        BigDecimal expectedM350 = expectedQuote(taskNum, "2.00", "1.200");
        assertThat(quoteFc30).isEqualByComparingTo(expectedFc30);
        assertThat(quoteM350).isEqualByComparingTo(expectedM350);
        assertThat(quoteM350).as("机型系数不同（1.000 vs 1.200），同一任务报价必须不同")
                .isNotEqualTo(quoteFc30);

        // 可复现：同一飞手同一任务重复应征 → 更新原记录、重新计价结果一致，列表不新增
        BigDecimal reQuoted = decimal(
                applyOk(fc30Rider, taskNum, modelId("FC30"), "").path("data"), "quotedAmount");
        assertThat(reQuoted).as("重复应征应得到同一平台报价").isEqualByComparingTo(quoteFc30);

        JsonNode list = applications(owner, taskNum);
        assertThat(list.size()).as("同一飞手重复应征不应新增记录").isEqualTo(2);
        assertThat(list.findValuesAsText("quotedAmount"))
                .containsExactlyInAnyOrder(quoteFc30.toPlainString(), quoteM350.toPlainString());
        assertThat(list.findValuesAsText("modelCode"))
                .containsExactlyInAnyOrder("FC30", "M350RTK");
    }

    @Test
    @DisplayName("客户端提交自定义金额（price 字段）被忽略：报价只来自服务端计算")
    void clientSubmittedPriceIsIgnored() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String taskNum = createTransportTask(owner, "2.00");
        TestAccounts.Account rider = riderMappedTo("FC30");

        JsonNode applied = applyOk(rider, taskNum, modelId("FC30"), ",\"price\":0.01,\"quotedAmount\":99999");
        BigDecimal quoted = decimal(applied.path("data"), "quotedAmount");
        BigDecimal expected = expectedQuote(taskNum, "2.00", "1.000");
        assertThat(quoted)
                .as("自定义金额必须被忽略，落库报价仍为平台公式结果")
                .isEqualByComparingTo(expected)
                .isNotEqualByComparingTo("0.01");
        assertThat(quoted).isNotEqualByComparingTo("99999");

        // 列表回读同样只暴露服务端报价
        JsonNode list = applications(owner, taskNum);
        assertThat(decimal(list.get(0), "quotedAmount")).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("未映射机型的设备应征 → AIRCRAFT_MODEL_REQUIRED；无机型字段同样拒绝")
    void unmappedDeviceApplyRejected() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String taskNum = createTransportTask(owner, "2.00");
        // 注册时绑定设备但未映射机型（旧注册路径）
        TestAccounts.Account rider = accounts().registerRider();
        assertThat(rider.djiId()).isNotNull();

        mockMvc.perform(post("/rider/apply")
                        .header("Authorization", rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskNum\":\"" + taskNum + "\",\"aircraftModelId\":"
                                + modelId("FC30") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ApiErrorCode.AIRCRAFT_MODEL_REQUIRED.getCode()));

        mockMvc.perform(post("/rider/apply")
                        .header("Authorization", rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskNum\":\"" + taskNum + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ApiErrorCode.AIRCRAFT_MODEL_REQUIRED.getCode()));

        assertThat(applications(owner, taskNum).size())
                .as("被门禁拒绝的应征不应落库")
                .isZero();
    }

    @Test
    @DisplayName("超重拒绝：货物重量超过机型最大载重 → EXCEEDS_PAYLOAD 且不落库")
    void exceedsPayloadRejected() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String taskNum = createTransportTask(owner, "10.00"); // ≤ FC30(30kg) 但 > M350(2.7kg)
        TestAccounts.Account fc30Rider = riderMappedTo("FC30");
        TestAccounts.Account m350Rider = riderMappedTo("M350RTK");

        mockMvc.perform(post("/rider/apply")
                        .header("Authorization", m350Rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskNum\":\"" + taskNum + "\",\"aircraftModelId\":"
                                + modelId("M350RTK") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.EXCEEDS_PAYLOAD.getCode()));

        assertThat(applications(owner, taskNum).size()).as("拒绝计价不得落库").isZero();

        // 边界内机型正常应征
        JsonNode applied = applyOk(fc30Rider, taskNum, modelId("FC30"), "");
        assertThat(decimal(applied.path("data"), "quotedAmount"))
                .isEqualByComparingTo(expectedQuote(taskNum, "10.00", "1.000"));
    }

    @Test
    @DisplayName("任务未填货物重量 → 应征被拒（INVALID_PARAM），不落库")
    void applyWithoutCargoWeightRejected() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String taskNum = createTransportTask(owner, null);
        TestAccounts.Account rider = riderMappedTo("FC30");

        mockMvc.perform(post("/rider/apply")
                        .header("Authorization", rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskNum\":\"" + taskNum + "\",\"aircraftModelId\":"
                                + modelId("FC30") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.INVALID_PARAM.getCode()));

        assertThat(applications(owner, taskNum).size()).isZero();
    }

    @Test
    @DisplayName("应征列表只读边界：非属主非应征普通用户 → 403")
    void applicationListOwnerOnly() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String taskNum = createTransportTask(owner, "2.00");
        TestAccounts.Account rider = riderMappedTo("FC30");
        applyOk(rider, taskNum, modelId("FC30"), "");

        TestAccounts.Account stranger = accounts().registerUser();
        mockMvc.perform(get("/task/" + taskNum + "/applications")
                        .header("Authorization", stranger.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        // 属主仍可查
        assertThat(applications(owner, taskNum).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("只读放行（TASK-BACKEND-007）：管理员与应征飞手 200，非应征飞手 403")
    void applicationListReadableByAdminAndApplicant() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String taskNum = createTransportTask(owner, "2.00");
        TestAccounts.Account rider = riderMappedTo("FC30");
        applyOk(rider, taskNum, modelId("FC30"), "");

        // 管理员（role=2，监管端旁听只读）→ 200
        TestAccounts.AdminAccount admin = accounts().adminLogin();
        mockMvc.perform(get("/task/" + taskNum + "/applications")
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].riderName").value(rider.userName()));

        // 应征飞手本人 → 200
        mockMvc.perform(get("/task/" + taskNum + "/applications")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // 非应征飞手（role=1）→ 403：权限=属主/应征/管理员
        TestAccounts.Account bystander = accounts().registerRider(UniqueNames.userName("rider"), null);
        mockMvc.perform(get("/task/" + taskNum + "/applications")
                        .header("Authorization", bystander.authorization()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("V3 迁移：flyway 历史、task 货物列、task_application 表与唯一约束落地")
    void v3MigrationApplied() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            // R10：information_schema / 字面量查询在 H2 MySQL 模式与 MySQL 8 双兼容
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = TRUE")) {
                assertThat(rs.next()).as("flyway_schema_history 无结果").isTrue();
                assertThat(rs.getInt(1)).as("V3__transport_application.sql 应已执行且成功").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns"
                            + " WHERE lower(table_name) = 'task'"
                            + " AND lower(column_name) IN ('cargo_weight_kg', 'cargo_category')")) {
                assertThat(rs.next()).as("information_schema 无结果").isTrue();
                assertThat(rs.getInt(1)).as("task 货物列应由 V3 创建").isEqualTo(2);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.table_constraints"
                            + " WHERE lower(table_name) = 'task_application'"
                            + " AND lower(constraint_name) = 'uk_task_application_task_rider'"
                            + " AND upper(constraint_type) = 'UNIQUE'")) {
                assertThat(rs.next()).as("information_schema 无结果").isTrue();
                assertThat(rs.getInt(1))
                        .as("task_application 唯一约束 (task_id, rider_id) 应由 V3 创建")
                        .isEqualTo(1);
            }
        }
    }
}

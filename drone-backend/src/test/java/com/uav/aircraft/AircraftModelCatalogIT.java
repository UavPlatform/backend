package com.uav.aircraft;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 机型库：V2 迁移落地 + {@code GET /api/aircraft-models} 查询（TASK-BACKEND-001 / REQ-BACKEND-001）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + MockMvc + {@code @Transactional} 回滚）；
 * R3：token 由 {@link TestAccounts} 真实注册取得；R6：AssertJ + 项目统一 MockMvc 断言。
 * 种子数据由 {@code V2__aircraft_model.sql} 在 Flyway 迁移时写入，测试只读断言 + 事务内追加行
 * （追加行随事务回滚，不影响共享 H2 库的其它用例）。
 */
class AircraftModelCatalogIT extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Test
    @DisplayName("V2 迁移：flyway 历史已执行、rider_uav.aircraft_model_id 列与外键落地、种子≥2")
    void v2MigrationApplied() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            // R10：flyway_schema_history / information_schema 查询在 H2 MySQL 模式与 MySQL 8 双兼容
            // （MySQL 布尔列以 TRUE 字面量比较；H2 该列为 BOOLEAN，= 1 反而不兼容）
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = TRUE")) {
                assertThat(rs.next()).as("flyway_schema_history 无结果").isTrue();
                assertThat(rs.getInt(1)).as("V2__aircraft_model.sql 应已执行且成功").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.key_column_usage"
                            + " WHERE lower(table_name) = 'rider_uav'"
                            + " AND lower(column_name) = 'aircraft_model_id'"
                            + " AND lower(constraint_name) = 'fk_rider_uav_aircraft_model'")) {
                assertThat(rs.next()).as("information_schema 无结果").isTrue();
                assertThat(rs.getInt(1))
                        .as("rider_uav.aircraft_model_id 外键 fk_rider_uav_aircraft_model 应由 V2 创建")
                        .isEqualTo(1);
            }
        }

        // 种子：≥2 种机型，含联调用 FlyCart 30 与 M350
        List<AircraftModel> seeds = aircraftModelRepository.findAll();
        assertThat(seeds).as("V2 种子应至少 2 种机型").hasSizeGreaterThanOrEqualTo(2);
        AircraftModel flyCart = seeds.stream()
                .filter(m -> "FC30".equals(m.getModelCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少种子机型 FC30 (FlyCart 30)"));
        AircraftModel m350 = seeds.stream()
                .filter(m -> "M350RTK".equals(m.getModelCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少种子机型 M350RTK"));
        assertThat(flyCart.getDisplayName()).contains("FlyCart 30");
        assertThat(flyCart.getMaxPayloadKg()).isGreaterThan(BigDecimal.ZERO);
        assertThat(flyCart.getEnabled()).isTrue();
        assertThat(flyCart.getTransportEnabled()).isTrue();
        // 机型参与报价（ADR-0003），两机型系数必须不同，否则同任务报价无法区分
        assertThat(flyCart.getCoefficient())
                .as("不同机型系数应不同（报价区分前提）")
                .isNotEqualTo(m350.getCoefficient());
    }

    @Test
    @DisplayName("查询 API：登录后可列出启用机型，含载重/系数/可吊运字段；未登录 401")
    void listEnabledModels() throws Exception {
        TestAccounts.Account rider = accounts().registerRider();
        String body = mockMvc.perform(get("/api/aircraft-models")
                        .header("Authorization", rider.authorization())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$..modelCode").value(hasItems("FC30", "M350RTK")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("FlyCart 30 应含载重 30.00kg 与可吊运标识（精确串断言避免 JSON 数字反序列化歧义）")
                .contains("\"maxPayloadKg\":30.00")
                .contains("\"transportEnabled\":true")
                .contains("\"coefficient\":");

        // 未登录不可见（JWT 拦截器全局生效）
        mockMvc.perform(get("/api/aircraft-models"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("查询 API：停用机型不返回；启用但不可吊运的机型返回且 transportEnabled=false")
    void disabledModelHiddenTransportFlagVisible() throws Exception {
        String disabledCode = UniqueNames.unique("mdl-disabled");
        String transportOffCode = UniqueNames.unique("mdl-ground");

        AircraftModel disabled = new AircraftModel();
        disabled.setModelCode(disabledCode);
        disabled.setDisplayName("停用机型");
        disabled.setMaxPayloadKg(new BigDecimal("1.00"));
        disabled.setCoefficient(new BigDecimal("1.000"));
        disabled.setEnabled(false);
        disabled.setTransportEnabled(true);
        aircraftModelRepository.save(disabled);

        AircraftModel transportOff = new AircraftModel();
        transportOff.setModelCode(transportOffCode);
        transportOff.setDisplayName("不可吊运机型");
        transportOff.setMaxPayloadKg(new BigDecimal("2.00"));
        transportOff.setCoefficient(new BigDecimal("1.000"));
        transportOff.setEnabled(true);
        transportOff.setTransportEnabled(false);
        aircraftModelRepository.save(transportOff);

        TestAccounts.Account rider = accounts().registerRider();
        String body = mockMvc.perform(get("/api/aircraft-models")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..modelCode").value(hasItems(transportOffCode)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("停用机型不得出现在目录中").doesNotContain(disabledCode);
        assertThat(body).contains("\"modelCode\":\"" + transportOffCode + "\"")
                .contains("\"transportEnabled\":false");
    }
}

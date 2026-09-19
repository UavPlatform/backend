package com.uav.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.OpenApiContract;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.pay.mapper.PayRecordRepository;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * t4 POC：后端能力验证的「真」端到端测试。
 *
 * <p>与仓库既有 24 个测试类的本质区别（既有测试的真实 HTTP 基础设施为 0）：
 * <ul>
 *   <li><b>真实 TCP HTTP</b>：应用以 RANDOM_PORT 起真实 Tomcat，用 JDK HttpClient 走 127.0.0.1:port；
 *       不使用 MockMvc（MockMvc 不经过网络栈与真实过滤器链）。</li>
 *   <li><b>真实数据库</b>：连接外部隔离 MySQL/MariaDB 实例（T4_DB_* 环境变量），
 *       不使用 H2、不 mock repository/service。</li>
 *   <li><b>真实账号与真实 JWT</b>：用户/飞手经 /user/register、/user/login、/rider/register 拿令牌，
 *       令牌只来自 HTTP 响应体；禁止自签 JWT 绕过认证链路（R3；迁移前测试树有 52 处此类写法，
 *       计数与复核命令见 docs/evidence/backend-test-baseline-2026-09-18.md，本类为 0 处调用）。</li>
 *   <li><b>无静默降级</b>：T4_DB_* 任一缺失时本类被 JUnit 显式禁用（skip），绝不回退 H2。</li>
 * </ul>
 *
 * <p>覆盖链路：建单（POST /task/create 生成 MissionOrder=PENDING）→ 支付（POST /pay/{orderNum}）
 * → 接单（POST /rider/accept，需飞手已绑定无人机）→ 交付（POST /rider/complete）。
 *
 * <p>schema 来源（**限定本 E2E profile**）：本 profile 显式关闭 Flyway（`spring.flyway.enabled=false`；主配置 `application.yml` 为 `true`），
 * schema 100% 由 Hibernate `ddl-auto=create-drop` 建立；Flyway 迁移链断裂由**独立干净库探针**证明（QA-B16），**不在本 profile 内复现**。
 *
 * <p><b>强制表述：本 harness 的结构保真未验证</b>（QA-B16）。schema 由本 profile 的 Hibernate `create-drop` 决定，
 * <b>不得</b>由此推出「迁移链健康」；要证明迁移链是坏的须走**独立探针**（独立类、独立命名，红才是预期）。
 *
 * <p>为何 profile 用 test 而非专用 e2e：com.uav.pay.MockPayGuard 把 mock 支付白名单硬编码为 [dev, test]
 * （src/main，测试不可改），非白名单 profile 会拒绝启用 mock 支付。故复用 test profile 满足门禁，
 * 同时用下面显式 properties 把数据源整体指向真实库（显式属性优先级最高，不依赖 profile 文件覆盖顺序）。
 *
 * <p>层次与驱动（O4/R9/R2/R4）：本类继承 {@link com.uav.support.RealProtocolTestBase}
 * （{@code RANDOM_PORT}，不含 {@code @Transactional}），是 R4 的<b>真实端口白名单成员</b>——
 * 它真实消费 `@LocalServerPort`（{@code base = "http://127.0.0.1:" + port}）与真库连接，
 * 因此不得降级为 MOCK；真实提交的隔离由「每次运行 create-drop 全新 schema + 唯一命名」提供（R5）。
 *
 * <p><b>环境门控与诚实性（O6/§9）</b>：需 {@code T4_DB_*} 指向<b>真实 MySQL</b>；缺省时本类
 * 由类级 {@code @EnabledIfEnvironmentVariable}（5 个变量，作用全部 4 个用例）整体<b>跳过</b>而非失败，
 * <b>绝不回退 H2</b>，也<b>不接受 MariaDB 顶替</b>——{@code effectiveJdbcUrlPointsAtOwnDatabase}
 * 断言后端 {@code DatabaseProductName} 含 "mysql" 正是这条红线。
 * 注意：缺环境时 failsafe 报「4 skipped」是<b>绿但未执行</b>，0 条断言真实执行，
 * 不得当作端到端已覆盖的正向证据。
 *
 * <p>为何两条用例必须留在本类而非拆分（O3 边界裁决）：{@code specConstrainsTheE2eSurface}
 * 依赖 RANDOM_PORT 对<b>运行中实例</b>发起真实 HTTP（读 {@code /v3/api-docs}）；
 * {@code qaB16DeclaredForeignKeyIsStillMissing} 是<b>真实数据库</b> schema 的特征化断言（依赖 {@code T4_DB_*}）。
 * 两者的前提恰是真实协议与真实库，不属于 O3 排除的「上下文加载、规格断言、特征化断言等<b>非端到端</b>用例」；
 * 拆分到 contract/ 只会重复环境门控或退化为静默跳过。
 *
 * <p>R8：ThreadLocal 由基类 {@code @AfterEach} 统一清理，本类不再手工清理。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // ── 真实数据库（全部来自环境变量；不设默认值 = 缺失即失败，绝不静默回退 H2）──
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://${T4_DB_HOST}:${T4_DB_PORT}/${T4_DB_NAME}"
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "spring.datasource.username=${T4_DB_USER}",
                "spring.datasource.password=${T4_DB_PASSWORD}",
                // ── harness 语义：create-drop（已实测不受跨 schema 误判影响），脚本初始化显式关闭 ──
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.show-sql=false",
                // 不钉死方言：主路径是 mysql:8.4（与生产同镜像），Hibernate 自动识别正确。
                // 仅当退回 MariaDB 12.3.3（最后手段）时才需外部追加：
                //   -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect
                // ── schema 来源：**只有 Hibernate create-drop** ──
                // 契约 §5-2 明令禁止同时开 `flyway.enabled` 与 `ddl-auto=create-drop`：那会让 V2 报
                // `success=1` 却从不落地声明的外键（QA-B16 假阳性）。实测见报告 §14。
                // 因此改为：Flyway 关闭；chat 三表（非 ORM 管理）直接执行**迁移文件本身**
                // （MySQL 方言，含 IF NOT EXISTS），而不是复制 H2 的 schema.sql（t2 建议）。
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=always",
                "spring.jpa.defer-datasource-initialization=true",
                "spring.sql.init.schema-locations=classpath:db/migration/chat/V1__init_chat_tables.sql",
                // ── 应用自身的 mock 支付开关（非测试替身）──
                "wechat.pay.mock-enabled=true",
                // ── 主 application.yml 必填占位符 ──
                "drone.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "drone.datasource.host=${T4_DB_HOST}",
                "drone.datasource.port=${T4_DB_PORT}",
                "drone.datasource.database=${T4_DB_NAME}",
                "drone.datasource.username=${T4_DB_USER}",
                "drone.datasource.password=${T4_DB_PASSWORD}",
                "drone.jwt.secret=t4-e2e-local-only-jwt-secret-0123456789abcdef",
                "drone.jwt.expiration=3600000",
                "drone.jwt.refresh-expiration=604800000",
                "drone.trtc.sdk-app-id=1400000001",
                "drone.trtc.secret-key=t4-e2e-placeholder-trtc-secret-key",
                "drone.trtc.room-expire=3600",
                "drone.amap.key=t4-e2e-placeholder-amap-key",
                "drone.amap.security-key=t4-e2e-placeholder-amap-security-key"
        })
@EnabledIfEnvironmentVariable(named = "T4_DB_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T4_DB_PORT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T4_DB_NAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T4_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T4_DB_PASSWORD", matches = ".+")
class OrderDeliveryE2EIT extends RealProtocolTestBase {

    private static final String RUN_ID = UniqueNames.unique("e2e");
    private static final String PASSWORD = "E2e!pw123456";

    /** 直接构造 Jackson 2 mapper：Boot 4 容器里没有 Jackson 2 类型的 JSON mapper bean，故不注入。 */
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    OrderRepository orders;

    @Autowired
    PayRecordRepository payRecords;

    /** 安全红线专用：生效数据源。用于断言「真实建立的那条连接的 JDBC URL」，而不是只看环境变量。 */
    @Autowired
    DataSource dataSource;

    private static int scalarInt(Connection c, String sql) throws Exception {
        try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("查询无结果: %s", sql).isTrue();
            return rs.getInt(1);
        }
    }

    private HttpClient http;
    private String base;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        base = "http://127.0.0.1:" + port;   // 真实 TCP，不是 MockMvc
    }

    /**
     * 安全红线（硬性）：<b>绝不允许本测试连到项目自有的 3306 容器</b>
     * （`drone-backend-mysql-1`，其 `drone_db` 可能载有操作者数据；`application-dev.yml` 默认就指向它）。
     *
     * <p>关键设计：本方法在 Spring 上下文创建<b>之前</b>执行（JUnit5 静态 @BeforeAll 早于 TestContext 初始化），
     * 所以一旦目标端口是 3306，就<b>立即失败且不会建立任何连接</b> —— 不是「先连上再断言」。
     */
    @BeforeAll
    static void refuseOperatorDatabase() {
        String port = System.getenv("T4_DB_PORT");
        assertThat(port)
                .as("T4_DB_PORT 必须显式提供，不得依赖 application-dev.yml 的 3306 默认值")
                .isNotNull();
        // ⚠️ 必须按【数值】比较端口，不能用字符串子串匹配："13306".contains("3306") == true，
        // 朴素子串判断会把合法的 13306 自己误判成违规（本测试首版即踩此坑，被正向用例当场抓出）。
        int parsed;
        try {
            parsed = Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            parsed = -1;
        }
        assertThat(parsed)
                .as("T4_DB_PORT 必须是可解析的端口号，实际值=%s（不得依赖 dev 默认值）", port)
                .isBetween(1, 65535);
        assertThat(parsed)
                .as("安全红线：T4_DB_PORT=%s 指向项目自有容器 drone-backend-mysql-1"
                        + "（application-dev.yml 的 127.0.0.1:3306/drone_db）。"
                        + "禁止对操作者数据库发起任何连接；请改用本次自建容器的端口（如 13306）。", parsed)
                .isNotEqualTo(3306);
    }

    @Test
    @DisplayName("QA-B16 特征化：V2 迁移声明的 fk_rider_uav_user 当前【不存在】（修链后本测试失败即为预期信号）")
    void qaB16DeclaredForeignKeyIsStillMissing() throws Exception {
        // 把 QA-B16 从「文档结论」变成「可回归的门禁」：实测锁定现状 —— 迁移脚本声明了
        // `CONSTRAINT fk_rider_uav_user FOREIGN KEY (user_id) REFERENCES user(id)`，但在本 harness 下
        // 该外键**从未落地**（全库外键数 0），因为建表实际由 Hibernate 完成。
        //
        // ⚠️ 预期信号：一旦 QA-B16 修复（需授权改 src/main），本断言会【失败】——那是好事。
        //    届时把 isZero() 翻转为 isEqualTo(1)，本测试即成为「迁移链已修复」的正向门禁。
        try (Connection c = dataSource.getConnection()) {
            int declaredFk = scalarInt(c,
                    "select count(*) from information_schema.table_constraints"
                            + " where table_schema = database()"
                            + " and constraint_name = 'fk_rider_uav_user'");
            int allFks = scalarInt(c,
                    "select count(*) from information_schema.table_constraints"
                            + " where table_schema = database() and constraint_type = 'FOREIGN KEY'");
            assertThat(declaredFk)
                    .as("fk_rider_uav_user 存在性：0 = QA-B16 未修复（修复后请翻转本断言）")
                    .isZero();
            assertThat(allFks)
                    .as("全库外键数：0 印证迁移声明的约束从未落地")
                    .isZero();
        }
    }

    @Test
    @DisplayName("安全红线：生效的 JDBC URL 必须指向本次自建容器，绝不可是 3306")
    void effectiveJdbcUrlPointsAtOwnTestDatabase() throws Exception {
        String expectedPort = System.getenv("T4_DB_PORT");
        try (Connection c = dataSource.getConnection()) {
            String url = c.getMetaData().getURL();
            // 从 //host:port/ 中【数值化】取出端口再判断（同样不能用子串匹配："13306" 含 "3306"）
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("//([^/:]+):(\\d+)/").matcher(url);
            assertThat(m.find()).as("生效 JDBC URL 形态异常: %s", url).isTrue();
            int livePort = Integer.parseInt(m.group(2));
            String liveHost = m.group(1);
            assertThat(liveHost).as("必须是本机回环地址，实际连接主机=%s", liveHost)
                    .isIn("127.0.0.1", "localhost", "::1");
            assertThat(livePort).as("安全红线：生效 JDBC 端口=%s 不得是 3306（项目自有容器 drone-backend-mysql-1）", livePort)
                    .isNotEqualTo(3306);
            assertThat(livePort).as("生效 JDBC 端口必须等于本次容器端口 %s", expectedPort)
                    .isEqualTo(Integer.parseInt(expectedPort.trim()));
            assertThat(url).as("必须连到本次运行的测试库 %s", System.getenv("T4_DB_NAME"))
                    .contains("/" + System.getenv("T4_DB_NAME"));
            assertThat(c.getMetaData().getDatabaseProductName())
                    .as("后端必须是真实 MySQL 协议数据库，不是 H2")
                    .containsIgnoringCase("mysql");
            assertThat(c.getCatalog()).as("必须连到本次运行的测试库")
                    .isEqualTo(System.getenv("T4_DB_NAME"));
        }
    }

    @Test
    @DisplayName("真实 HTTP + 真实库 + 真实账号：建单 → 支付 → 接单 → 交付 全链路")
    void fullOrderLifecycleOverRealHttpAndRealDatabase() throws Exception {
        // ── 1. 真实注册 + 真实登录，JWT 只来自 HTTP 响应体 ──
        String userName = "e2e_u_" + RUN_ID;
        JsonNode reg = post("/user/register", null,
                "{\"userName\":\"" + userName + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(reg.path("success").asBoolean()).as("register resp=%s", reg).isTrue();

        JsonNode login = post("/user/login", null,
                "{\"userName\":\"" + userName + "\",\"password\":\"" + PASSWORD + "\"}");
        String userToken = login.path("data").path("token").asText();
        assertThat(userToken).as("login resp=%s", login).isNotBlank();

        // ── 2. 建单：POST /task/create 同时生成 task 与 MissionOrder(PENDING) ──
        String taskBody = "{\"taskName\":\"e2e-task-" + RUN_ID + "\",\"type\":\"SURVEY\","
                + "\"description\":\"t4 e2e\",\"reward\":100.0,\"waypoints\":["
                + "{\"orderIndex\":0,\"longitude\":121.0,\"latitude\":31.0,\"altitude\":100.0},"
                + "{\"orderIndex\":1,\"longitude\":121.01,\"latitude\":31.0,\"altitude\":100.0}]}";
        JsonNode created = post("/task/create", userToken, taskBody);
        assertThat(created.path("success").asBoolean()).as("create resp=%s", created).isTrue();
        String taskNum = created.path("data").path("taskNum").asText();
        String orderNum = created.path("data").path("orderNum").asText();
        assertThat(taskNum).as("taskNum from resp=%s", created).isNotBlank();
        assertThat(orderNum).as("orderNum from resp=%s", created).isNotBlank();

        MissionOrder order = orders.findByOrderNum(orderNum)
                .orElseThrow(() -> new AssertionError("DB 内无该订单: " + orderNum));
        assertThat(order.getOrderStatus().name()).isEqualTo("PENDING");
        assertThat(order.getUserId()).isNotNull();

        // ── 3. 支付（应用自带 mock 支付开关 wechat.pay.mock-enabled=true，非测试替身） ──
        JsonNode paid = post("/pay/" + orderNum, userToken, null);
        assertThat(paid.path("success").asBoolean()).as("pay resp=%s", paid).isTrue();
        assertThat(orders.findByOrderNum(orderNum).orElseThrow().getOrderStatus().name())
                .as("支付后订单状态应为 PAID")
                .isEqualTo("PAID");
        assertThat(payRecords.findByOrderNum(orderNum)).as("支付流水应真实落库").isPresent();

        // ── 4. 飞手真实注册（含绑定无人机）+ 真实登录 → 接单 ──
        String riderName = "e2e_r_" + RUN_ID;
        JsonNode riderReg = post("/rider/register", null,
                "{\"userName\":\"" + riderName + "\",\"password\":\"" + PASSWORD + "\","
                + "\"djiId\":\"E2E-DJI-" + RUN_ID + "\"}");
        String riderToken = riderReg.path("data").path("token").asText();
        assertThat(riderToken).as("rider register resp=%s", riderReg).isNotBlank();

        JsonNode accepted = post("/rider/accept?taskNum=" + taskNum, riderToken, null);
        assertThat(accepted.path("success").asBoolean()).as("accept resp=%s", accepted).isTrue();

        // ── 5. 交付 ──
        JsonNode done = post("/rider/complete?taskNum=" + taskNum + "&note=t4-e2e-delivered",
                riderToken, null);
        assertThat(done.path("success").asBoolean()).as("complete resp=%s", done).isTrue();

        // ── 6. 终态校验：HTTP 层与数据库层必须一致 ──
        JsonNode detail = get("/task/detail?taskNum=" + taskNum, userToken);
        assertThat(detail.path("success").asBoolean()).as("detail resp=%s", detail).isTrue();
        assertThat(detail.path("data").path("taskStatus").asText())
                .as("交付后任务状态, detail=%s", detail)
                .isEqualTo("COMPLETED");

        String dbOrderStatus = orders.findByOrderNum(orderNum).orElseThrow()
                .getOrderStatus().name();
        String httpOrderStatus = detail.path("data").path("orderStatus").asText();
        assertThat(httpOrderStatus)
                .as("HTTP 报的订单状态必须与真实数据库一致 (db=%s)", dbOrderStatus)
                .isEqualTo(dbOrderStatus);
        assertThat(dbOrderStatus).as("交付后订单不应仍为 PENDING/PAID").isNotIn("PENDING", "PAID");
        // 终态字面量断言：交付完成后「任务 = COMPLETED、订单 = WAITING_CONFIRM（待确认完成）」
        // （订单终态由 TaskServiceImpl 的完成动作置为 WAITING_CONFIRM，随后由验收/超时自动确认转 COMPLETED）
        assertThat(detail.path("data").path("taskStatus").asText())
                .as("交付后任务终态").isEqualTo("COMPLETED");
        assertThat(httpOrderStatus).as("交付后订单终态（HTTP 层）").isEqualTo("WAITING_CONFIRM");
        assertThat(dbOrderStatus).as("交付后订单终态（数据库层）").isEqualTo("WAITING_CONFIRM");
    }

    @Test
    @DisplayName("规格约束：E2E 用到的端点与请求字段必须在运行中 OpenAPI 文档中声明（防漂移）")
    void specConstrainsTheE2eSurface() throws Exception {
        JsonNode live = get("/v3/api-docs", null);
        assertThat(live.path("openapi").asText()).as("OpenAPI 版本").startsWith("3.");

        // 冻结规格 = backend/spec/openapi/drone-backend.openapi.json（归一化后的 SSOT）
        JsonNode frozen = OpenApiContract.readFrozen();
        Set<String> liveOps = operations(live);
        Set<String> frozenOps = operations(frozen);
        assertThat(liveOps).as("运行中接口集合与冻结规格不一致（实现已漂移，需重新导出规格）")
                .isEqualTo(frozenOps);
        assertThat(liveOps).as("规格文件不应为空").hasSizeGreaterThan(80);

        assertThat(liveOps).contains(
                "POST /user/register", "POST /user/login", "POST /task/create",
                "POST /pay/{orderNum}", "POST /rider/register",
                "POST /rider/accept", "POST /rider/complete", "GET /task/detail");

        Set<String> taskFields = declaredProperties(live, "/task/create", "post");
        assertThat(taskFields).as("E2E 发送的建单字段未被规格声明")
                .contains("taskName", "type", "description", "reward", "waypoints");
        // ── A7（已升级）：servers 不再需要「显式忽略」 ──
        // 导出统一走 OpenApiContract.canonicalize：随机端口被擦洗、servers 固定为相对基址，
        // 因此整份文档（含 servers/info）都可纳入门禁；字段级漂移由
        // com.uav.contract.OpenApiContractGateE2EIT#frozenSpecMatchesRunningImplementation 做整文档深比较兜住。
        assertThat(frozen.path("servers").path(0).path("url").asText())
                .as("冻结规格的 servers 应已归一化为相对基址（导出时擦除随机端口）").isEqualTo("/");

        Set<String> regFields = declaredProperties(live, "/user/register", "post");
        assertThat(regFields).as("E2E 发送的注册字段未被规格声明")
                .contains("userName", "password");
    }

    private static Set<String> operations(JsonNode spec) {
        Set<String> out = new TreeSet<>();
        spec.path("paths").properties().forEach(p ->
                p.getValue().properties().forEach(op ->
                        out.add(op.getKey().toUpperCase() + " " + p.getKey())));
        return out;
    }

    private static Set<String> declaredProperties(JsonNode spec, String path, String method) {
        JsonNode schema = spec.path("paths").path(path).path(method)
                .path("requestBody").path("content").path("application/json").path("schema");
        return resolvePropertyNames(spec, schema);
    }

    private static Set<String> resolvePropertyNames(JsonNode spec, JsonNode schema) {
        Set<String> out = new TreeSet<>();
        JsonNode ref = schema.path("$ref");
        if (!ref.isMissingNode() && ref.asText().startsWith("#/")) {
            JsonNode resolved = spec;
            for (String seg : ref.asText().substring(2).split("/")) {
                resolved = resolved.path(seg);
            }
            return resolvePropertyNames(spec, resolved);
        }
        schema.path("properties").properties().forEach(p -> out.add(p.getKey()));
        schema.path("allOf").forEach(sub -> out.addAll(resolvePropertyNames(spec, sub)));
        return out;
    }

    // ─────────── 真实 HTTP 工具（无 MockMvc） ───────────

    private JsonNode post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return send(b.build());
    }

    private JsonNode get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return send(b.build());
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode())
                .as("HTTP %s %s 期望 200，实际 %s，body=%s",
                        req.method(), req.uri().getPath(), resp.statusCode(), resp.body())
                .isEqualTo(200);
        return json.readTree(resp.body());
    }
}

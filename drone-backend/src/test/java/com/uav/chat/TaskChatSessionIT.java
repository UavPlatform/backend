package com.uav.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.chat.repository.ChatMessageRepository;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.TaskStatus;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 聊天会话绑定 taskNum（TASK-BACKEND-005 / REQ-BACKEND-001 / ADR-0003）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + MockMvc + {@code @Transactional} 回滚）；
 * R3：token 由 {@link TestAccounts} 真实注册取得；R6：AssertJ + 项目统一 MockMvc 断言；
 * R7：任务/应征造数走 {@link com.uav.support.TestFixtures} 与 {@link UniqueNames}。
 *
 * <p>覆盖：任务会话创建绑定 taskNum+applicationId；同任务同飞手去重复用；
 * 按 taskNum 聚合（属主全量、飞手只见自己、越权 403、任务不存在 404、未读数/最后一条消息）；
 * 越权发送被拒且不落库、成员发送成功；创建权限与参数校验；非任务会话不受任务字段影响；
 * V4 迁移落地（R10 双兼容查询）。
 */
class TaskChatSessionIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private TaskApplicationRepository applicationRepository;

    @Autowired
    private DataSource dataSource;

    /** 属主 + 一名已应征飞手 + 任务。 */
    private record Scenario(TestAccounts.Account owner, TestAccounts.Account rider, Task task) {
    }

    private Scenario taskWithApplicant() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        Task task = fixtures.task(TaskStatus.IDLE, 0.0, owner.id());
        fixtures.taskApplication(task, rider.id());
        return new Scenario(owner, rider, task);
    }

    private TaskApplication applicationOf(Task task, long riderId) {
        return applicationRepository.findByTaskIdAndRiderId(task.getId(), riderId)
                .orElseThrow(() -> new AssertionError("应征记录不存在: task=" + task.getId()
                        + ", rider=" + riderId));
    }

    /** 任务会话请求体：type=0，userIds=[对方]（调用方由服务端并入）。 */
    private ObjectNode taskSessionBody(String taskNum, long counterpartId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", UniqueNames.unique("tc"));
        body.put("type", 0);
        body.put("taskNum", taskNum);
        ArrayNode userIds = body.putArray("userIds");
        userIds.add(counterpartId);
        return body;
    }

    /** POST /chat/session/create，返回原始响应体（状态码由调用方断言）。 */
    private String createSession(TestAccounts.Account caller, ObjectNode body) throws Exception {
        return mockMvc.perform(post("/chat/session/create")
                        .header("Authorization", caller.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
    }

    /** 创建成功断言封装：返回 data 节点。 */
    private JsonNode createOk(TestAccounts.Account caller, ObjectNode body) throws Exception {
        String resp = createSession(caller, body);
        JsonNode json = MAPPER.readTree(resp);
        assertThat(json.path("success").asBoolean()).as("创建会话应成功，响应=%s", resp).isTrue();
        return json.path("data");
    }

    /** POST /chat/Message/send，返回 HTTP 状态码。 */
    private int sendMessage(TestAccounts.Account caller, long sessionId, String content,
                            StringBuilder bodyOut) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("sessionId", sessionId);
        body.put("content", content);
        var result = mockMvc.perform(post("/chat/Message/send")
                        .header("Authorization", caller.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andReturn();
        if (bodyOut != null) {
            bodyOut.setLength(0);
            bodyOut.append(result.getResponse().getContentAsString());
        }
        return result.getResponse().getStatus();
    }

    private JsonNode listTaskSessions(TestAccounts.Account caller, String taskNum) throws Exception {
        String body = mockMvc.perform(get("/task/" + taskNum + "/chat-sessions")
                        .header("Authorization", caller.authorization()))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    private static List<Long> memberIds(JsonNode sessionData) {
        return MAPPER.convertValue(sessionData.path("userIds"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, Long.class));
    }

    @Test
    @DisplayName("创建任务会话：绑定 taskNum + applicationId，参与方为任务属主 ↔ 应征飞手")
    void createTaskSessionBindsTaskAndApplication() throws Exception {
        Scenario s = taskWithApplicant();

        JsonNode data = createOk(s.owner(), taskSessionBody(s.task().getTaskNum(), s.rider().id()));

        assertThat(data.path("taskNum").asText()).isEqualTo(s.task().getTaskNum());
        assertThat(data.path("applicationId").asLong())
                .isEqualTo(applicationOf(s.task(), s.rider().id()).getId());
        assertThat(data.path("type").asInt()).isZero();
        assertThat(memberIds(data)).containsExactlyInAnyOrder(s.owner().id(), s.rider().id());
        assertThat(chatSessionRepository.findByTaskNum(s.task().getTaskNum())).hasSize(1);
    }

    @Test
    @DisplayName("去重：同一任务+同一飞手重复创建复用同一会话（属主/飞手两侧发起一致）")
    void duplicateCreateReusesSameSession() throws Exception {
        Scenario s = taskWithApplicant();

        JsonNode first = createOk(s.owner(), taskSessionBody(s.task().getTaskNum(), s.rider().id()));
        JsonNode again = createOk(s.owner(), taskSessionBody(s.task().getTaskNum(), s.rider().id()));
        // 飞手侧发起（参与方为属主）也命中同一去重键
        JsonNode byRider = createOk(s.rider(), taskSessionBody(s.task().getTaskNum(), s.owner().id()));

        assertThat(again.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(byRider.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(chatSessionRepository.findByTaskNum(s.task().getTaskNum())).hasSize(1);

        // 不同飞手 → 不同会话
        TestAccounts.Account rider2 = accounts().registerRider();
        fixtures.taskApplication(s.task(), rider2.id());
        JsonNode second = createOk(s.owner(), taskSessionBody(s.task().getTaskNum(), rider2.id()));
        assertThat(second.path("id").asLong()).isNotEqualTo(first.path("id").asLong());
        assertThat(chatSessionRepository.findByTaskNum(s.task().getTaskNum())).hasSize(2);
    }

    @Test
    @DisplayName("按 taskNum 聚合：属主见全部含飞手/applicationId；飞手只见自己；越权 403、任务不存在 404")
    void listTaskSessionsAggregatesAndAuthorizes() throws Exception {
        Scenario s = taskWithApplicant();
        TestAccounts.Account rider2 = accounts().registerRider();
        fixtures.taskApplication(s.task(), rider2.id());

        JsonNode s1 = createOk(s.owner(), taskSessionBody(s.task().getTaskNum(), s.rider().id()));
        JsonNode s2 = createOk(s.owner(), taskSessionBody(s.task().getTaskNum(), rider2.id()));

        // 属主：两个会话，含对方飞手、applicationId（两条应征各关联一次）
        JsonNode ownerList = listTaskSessions(s.owner(), s.task().getTaskNum());
        assertThat(ownerList.path("success").asBoolean()).isTrue();
        JsonNode items = ownerList.path("data");
        assertThat(items.size()).isEqualTo(2);
        Set<Long> applicationIds = new HashSet<>();
        Set<Long> sessionIds = new HashSet<>();
        for (JsonNode item : items) {
            assertThat(item.path("taskNum").asText()).isEqualTo(s.task().getTaskNum());
            assertThat(item.path("riderName").asText()).isNotBlank();
            assertThat(item.path("otherUserName").asText()).isNotBlank();
            assertThat(item.path("unreadCount").isMissingNode()
                    || item.path("unreadCount").isNull()).isFalse();
            applicationIds.add(item.path("applicationId").asLong());
            sessionIds.add(item.path("sessionId").asLong());
        }
        assertThat(sessionIds).containsExactlyInAnyOrder(
                s1.path("id").asLong(), s2.path("id").asLong());
        assertThat(applicationIds).containsExactlyInAnyOrder(
                applicationOf(s.task(), s.rider().id()).getId(),
                applicationOf(s.task(), rider2.id()).getId());

        // 飞手：仅自己参与的会话，对方为任务属主
        JsonNode riderList = listTaskSessions(s.rider(), s.task().getTaskNum());
        assertThat(riderList.path("data").size()).isEqualTo(1);
        JsonNode own = riderList.path("data").get(0);
        assertThat(own.path("sessionId").asLong()).isEqualTo(s1.path("id").asLong());
        assertThat(own.path("otherUserName").asText()).isEqualTo(s.owner().userName());

        // 越权：非属主非应征飞手 → 403 NO_PERMISSION
        TestAccounts.Account stranger = accounts().registerUser();
        mockMvc.perform(get("/task/" + s.task().getTaskNum() + "/chat-sessions")
                        .header("Authorization", stranger.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.NO_PERMISSION.getCode()));

        // 任务不存在 → 404
        mockMvc.perform(get("/task/" + UniqueNames.unique("TN") + "/chat-sessions")
                        .header("Authorization", s.owner().authorization()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未读数：飞手发消息后属主侧任务会话 unreadCount=1 且 lastMessage 可见")
    void unreadCountReflectsRiderMessage() throws Exception {
        Scenario s = taskWithApplicant();

        JsonNode session = createOk(s.owner(), taskSessionBody(s.task().getTaskNum(), s.rider().id()));
        long sessionId = session.path("id").asLong();

        // 会话创建时 lastReadTime=创建时刻，等时间推进后再发消息保证 create_time > lastReadTime
        Thread.sleep(20);
        assertThat(sendMessage(s.rider(), sessionId, "吊点在厂区北门", null)).isEqualTo(200);

        JsonNode items = listTaskSessions(s.owner(), s.task().getTaskNum()).path("data");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("unreadCount").asInt()).isEqualTo(1);
        assertThat(items.get(0).path("lastMessage").asText()).isEqualTo("吊点在厂区北门");
    }

    @Test
    @DisplayName("越权发送：非成员向任务会话发消息 → 403 且不落库；成员发送成功")
    void sendMessageRequiresTaskSessionMembership() throws Exception {
        Scenario s = taskWithApplicant();
        JsonNode session = createOk(s.owner(), taskSessionBody(s.task().getTaskNum(), s.rider().id()));
        long sessionId = session.path("id").asLong();
        long messagesBefore = chatMessageRepository.count();

        TestAccounts.Account stranger = accounts().registerUser();
        StringBuilder body = new StringBuilder();
        int status = sendMessage(stranger, sessionId, "越权消息", body);
        assertThat(status).isEqualTo(403);
        assertThat(MAPPER.readTree(body.toString()).path("errorCode").asText())
                .isEqualTo(ApiErrorCode.NO_PERMISSION.getCode());
        assertThat(chatMessageRepository.count()).isEqualTo(messagesBefore);

        // 成员（应征飞手）发送成功并落库
        assertThat(sendMessage(s.rider(), sessionId, "正常消息", null)).isEqualTo(200);
        assertThat(chatMessageRepository.count()).isEqualTo(messagesBefore + 1);
    }

    @Test
    @DisplayName("创建权限：非属主非应征飞手 403；属主与未应征飞手建会话 403")
    void createTaskSessionPermissionChecks() throws Exception {
        Scenario s = taskWithApplicant();

        // 局外人发起 → 403
        TestAccounts.Account stranger = accounts().registerUser();
        JsonNode r1 = MAPPER.readTree(createSession(stranger,
                taskSessionBody(s.task().getTaskNum(), s.rider().id())));
        assertThat(r1.path("errorCode").asText()).isEqualTo(ApiErrorCode.NO_PERMISSION.getCode());

        // 未应征飞手发起 → 403
        TestAccounts.Account noApplicant = accounts().registerRider();
        JsonNode r2 = MAPPER.readTree(createSession(noApplicant,
                taskSessionBody(s.task().getTaskNum(), s.owner().id())));
        assertThat(r2.path("errorCode").asText()).isEqualTo(ApiErrorCode.NO_PERMISSION.getCode());

        // 属主与未应征飞手建会话 → 403（对方必须有应征记录）
        JsonNode r3 = MAPPER.readTree(createSession(s.owner(),
                taskSessionBody(s.task().getTaskNum(), noApplicant.id())));
        assertThat(r3.path("errorCode").asText()).isEqualTo(ApiErrorCode.NO_PERMISSION.getCode());

        assertThat(chatSessionRepository.findByTaskNum(s.task().getTaskNum())).isEmpty();
    }

    @Test
    @DisplayName("创建校验：type≠0 → 400；applicationId 不一致 → 400；参与方缺属主 → 400；任务不存在 → 404")
    void createTaskSessionValidation() throws Exception {
        Scenario s = taskWithApplicant();

        // type=1（非一对一会话）
        ObjectNode group = taskSessionBody(s.task().getTaskNum(), s.rider().id());
        group.put("type", 1);
        JsonNode r1 = MAPPER.readTree(createSession(s.owner(), group));
        assertThat(r1.path("errorCode").asText()).isEqualTo(ApiErrorCode.INVALID_PARAM.getCode());

        // applicationId 与实际应征记录不一致
        ObjectNode mismatch = taskSessionBody(s.task().getTaskNum(), s.rider().id());
        mismatch.put("applicationId", 999_999L);
        JsonNode r2 = MAPPER.readTree(createSession(s.owner(), mismatch));
        assertThat(r2.path("errorCode").asText()).isEqualTo(ApiErrorCode.INVALID_PARAM.getCode());

        // 参与方不含任务属主（清空 userIds，调用方并入后只剩自己）
        ObjectNode noOwner = taskSessionBody(s.task().getTaskNum(), s.rider().id());
        ((ArrayNode) noOwner.get("userIds")).removeAll();
        JsonNode r3 = MAPPER.readTree(createSession(s.owner(), noOwner));
        assertThat(r3.path("errorCode").asText()).isEqualTo(ApiErrorCode.INVALID_PARAM.getCode());

        // 任务不存在 → 404
        JsonNode r4 = MAPPER.readTree(createSession(s.owner(),
                taskSessionBody(UniqueNames.unique("TN"), s.rider().id())));
        assertThat(r4.path("code").asInt()).isEqualTo(404);

        assertThat(chatSessionRepository.findByTaskNum(s.task().getTaskNum())).isEmpty();
    }

    @Test
    @DisplayName("非任务会话不受任务字段影响：不传 taskNum 走通用链路，taskNum/applicationId 恒为空")
    void genericSessionUnaffectedByTaskFields() throws Exception {
        TestAccounts.Account a = accounts().registerUser();
        TestAccounts.Account b = accounts().registerUser();

        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", UniqueNames.unique("g"));
        body.put("type", 0);
        body.put("applicationId", 42L); // 无 taskNum 时应被忽略
        body.putArray("userIds").add(b.id());

        JsonNode data = createOk(a, body);
        assertThat(asNull(data.path("taskNum"))).isTrue();
        assertThat(asNull(data.path("applicationId"))).isTrue();
    }

    /** JSON 中字段为 null 或缺失都视为空（兼容序列化包含/排除 null 两种配置）。 */
    private static boolean asNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    @Test
    @DisplayName("V4 迁移：flyway 历史与 chat_session task_num/application_id 列落地")
    void v4MigrationApplied() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            // R10：information_schema / 字面量查询在 H2 MySQL 模式与 MySQL 8 双兼容
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = TRUE")) {
                assertThat(rs.next()).as("flyway_schema_history 无结果").isTrue();
                assertThat(rs.getInt(1)).as("V4__chat_session_task.sql 应已执行且成功").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns"
                            + " WHERE lower(table_name) = 'chat_session'"
                            + " AND lower(column_name) IN ('task_num', 'application_id')")) {
                assertThat(rs.next()).as("information_schema 无结果").isTrue();
                assertThat(rs.getInt(1)).as("chat_session 应有 task_num 与 application_id 两列").isEqualTo(2);
            }
        }
    }
}

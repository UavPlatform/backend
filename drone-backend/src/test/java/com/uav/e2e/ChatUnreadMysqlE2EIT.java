package com.uav.e2e;

import com.uav.chat.repository.ChatMessageRepository;
import com.uav.support.RealProtocolTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未读数原生 SQL（{@link ChatMessageRepository#countUnread}）的<b>真实 MySQL</b> 覆盖（R10）。
 *
 * <p>该查询依赖方言细节：MySQL 把 JSON 列转字符串时形如 {@code [4, 5]}（带空格），H2 的
 * {@code CAST(... AS CHAR)} 默认长度为 1。H2 集成测试（UnreadSemanticsIT）只能证明 H2 行为，
 * 所以这里在 CI 的 MySQL 8.4 服务容器（13306）上逐条验证语义：
 * 本人消息不计、他人与系统通知计入、已撤回不计、本人删除（JSON 首/中/尾元素）不计、
 * 仅他人删除照常计入、用户 ID 子串（4 vs 14/40）不误伤、since 之前的消息不计。
 *
 * <p>数据隔离（R5）：直接写 {@code chat_messages}（无外键），使用随机会话 ID 与唯一 msg_id，
 * 真实提交，用例结束按会话 ID 清理。连接门控与端口红线同 {@link OrderDeliveryE2EIT}。
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
                // ── schema：Flyway baseline + JPA validate（ADR-0001）──
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.show-sql=false",
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.sql.init.mode=never",
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
class ChatUnreadMysqlE2EIT extends RealProtocolTestBase {

    private static final long ME = 4L;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private long sessionId;

    @BeforeAll
    static void refuseOperatorDatabase() {
        int port = Integer.parseInt(System.getenv("T4_DB_PORT").trim());
        assertThat(port).as("安全红线：不得连接 3306 上的操作者数据库").isNotEqualTo(3306);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId);
    }

    private void insert(long from, long createTime, int status, String deletedByJson) {
        jdbc.update("INSERT INTO chat_messages (msg_id, from_user_id, session_id, content, status,"
                        + " deleted_by_user_ids, create_time, msg_type) VALUES (?, ?, ?, ?, ?, "
                        + (deletedByJson == null ? "NULL" : "CAST(? AS JSON)") + ", ?, 0)",
                deletedByJson == null
                        ? new Object[]{UUID.randomUUID().toString(), from, sessionId, "m", status, createTime}
                        : new Object[]{UUID.randomUUID().toString(), from, sessionId, "m", status,
                                deletedByJson, createTime});
    }

    @Test
    @DisplayName("R10：未读数 SQL 在真实 MySQL 上的语义（本人/系统/撤回/JSON 删除成员/ID 子串/since）")
    void unreadCountSemanticsOnRealMysql() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class))
                .as("必须是真实 MySQL").doesNotContainIgnoringCase("h2");
        sessionId = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        long since = 1_000L;

        insert(ME, 2_000L, 0, null);                 // 本人发送 → 不计
        insert(7L, 2_001L, 0, null);                 // 他人 → 计 1
        insert(0L, 2_002L, 0, null);                 // 系统通知 → 计 2
        insert(7L, 2_003L, 2, null);                 // 已撤回 → 不计
        insert(7L, 2_004L, 0, "[4]");                // 本人删除（唯一元素）→ 不计
        insert(7L, 2_005L, 0, "[4, 7]");             // 本人删除（首元素）→ 不计
        insert(7L, 2_006L, 0, "[7, 9, 4]");          // 本人删除（尾元素）→ 不计
        insert(7L, 2_007L, 0, "[7, 4, 9]");          // 本人删除（中间元素）→ 不计
        insert(7L, 2_008L, 0, "[7]");                // 仅他人删除 → 计 3
        insert(7L, 2_009L, 0, "[14, 40, 41]");       // ID 子串不误伤 → 计 4
        insert(7L, 2_010L, 0, "[]");                 // 空数组 → 计 5
        insert(7L, 500L, 0, null);                   // since 之前 → 不计

        // 前提校验：MySQL 的 JSON→CHAR 确实带空格（正是 H2 测不到的方言差异）
        assertThat(jdbc.queryForObject(
                "SELECT CAST(deleted_by_user_ids AS CHAR(4000)) FROM chat_messages"
                        + " WHERE session_id = ? AND create_time = 2005", String.class, sessionId))
                .isEqualTo("[4, 7]");

        assertThat(chatMessageRepository.countUnread(sessionId, since, ME)).isEqualTo(5);
        // 对发送方 7：自己发的全部不计，只剩本人(4)发送的 1 条 + 系统通知 1 条；
        // 7 自己删除的 [4, 7] / [7, 9, 4] / [7, 4, 9] / [7] 本就是 7 发送，不影响计数
        assertThat(chatMessageRepository.countUnread(sessionId, since, 7L)).isEqualTo(2);
        // 用户 14：10 条有效消息中仅 [14, 40, 41] 那条被其删除
        assertThat(chatMessageRepository.countUnread(sessionId, since, 14L)).isEqualTo(9);
    }
}

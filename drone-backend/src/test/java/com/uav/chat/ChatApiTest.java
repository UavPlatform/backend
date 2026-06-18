package com.uav.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chat 模块接口集成测试
 * 覆盖 SessionController 和 MessageController 所有接口
 * <p>
 * 运行前确保：Spring Boot 应用已启动在默认配置的数据库中
 * 测试流程：注册 → 登录 → 创建会话 → 发送消息 → 查询消息 → 撤回消息 → 删除消息 → 列出会话 → 删除会话
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl;
    private String token;
    private Long userId;

    // 测试过程中产生的数据
    private Long createdSessionId;
    private String sentMsgId;

    @BeforeAll
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    private <T> HttpEntity<T> authRequest(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    // ==================== 1. 注册 ====================

    @Test
    @Order(1)
    @DisplayName("用户注册")
    void testRegister() {
        String testUserName = "chat_test_" + System.currentTimeMillis() % 100000;
        Map<String, String> body = Map.of("userName", testUserName, "password", "123456");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + "/user/register", authRequest(body), String.class);

        System.out.println("[register] status=" + resp.getStatusCode() + " body=" + resp.getBody());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("\"success\":true"));

        try {
            JsonNode json = objectMapper.readTree(resp.getBody());
            userId = json.get("data").get("id").asLong();
            assertNotNull(userId);
        } catch (Exception e) {
            fail("解析注册响应失败: " + e.getMessage());
        }
    }

    // ==================== 2. 登录 ====================

    @Test
    @Order(2)
    @DisplayName("用户登录获取JWT")
    void testLogin() {
        String testUserName = "chat_test_" + System.currentTimeMillis() % 100000;
        // 注册一个新用户然后立即登录（因为注册在 @Order(1) 用了不同的用户名）
        // 这里直接用现有用户 wmc 来测，或重新注册
        // 方法：注册一个用户，然后登录
        Map<String, String> loginBody = Map.of("userName", "wmc", "password", "123456");

        // 尝试用已知用户登录，如果失败则注册新用户
        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                baseUrl + "/user/login", authRequest(loginBody), String.class);

        // 如果wmc登录失败（密码不对），注册新用户
        if (loginResp.getStatusCode() != HttpStatus.OK || !loginResp.getBody().contains("\"success\":true")) {
            String newUser = "chat_test_" + System.currentTimeMillis();
            Map<String, String> regBody = Map.of("userName", newUser, "password", "123456");
            restTemplate.postForEntity(baseUrl + "/user/register", authRequest(regBody), String.class);

            loginBody = Map.of("userName", newUser, "password", "123456");
            loginResp = restTemplate.postForEntity(baseUrl + "/user/login", authRequest(loginBody), String.class);
        }

        System.out.println("[login] status=" + loginResp.getStatusCode() + " body=" + loginResp.getBody());

        assertEquals(HttpStatus.OK, loginResp.getStatusCode());
        try {
            JsonNode json = objectMapper.readTree(loginResp.getBody());
            token = json.get("data").get("token").asText();
            userId = json.get("data").get("userId") != null
                    ? json.get("data").get("userId").asLong() : userId;
            assertNotNull(token, "JWT token 不能为空");
            System.out.println("[login] 获取到 token, userId=" + userId);
        } catch (Exception e) {
            fail("解析登录响应失败: " + e.getMessage());
        }
    }

    // ==================== 3. 创建会话 ====================

    @Test
    @Order(3)
    @DisplayName("创建聊天会话")
    void testCreateSession() {
        Map<String, Object> body = Map.of(
                "name", "集成测试会话",
                "type", 0,
                "userIds", new Long[]{userId}
        );

        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + "/chat/session/create", authRequest(body), String.class);

        System.out.println("[createSession] status=" + resp.getStatusCode() + " body=" + resp.getBody());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("\"success\":true"));

        // 创建成功后，从列表里拿到 sessionId
        ResponseEntity<String> listResp = restTemplate.exchange(
                baseUrl + "/chat/session/list",
                HttpMethod.GET,
                authRequest(null),
                String.class);

        System.out.println("[listSession after create] body=" + listResp.getBody());

        try {
            JsonNode json = objectMapper.readTree(listResp.getBody());
            assertTrue(json.get("success").asBoolean());
            if (json.get("data").isArray() && json.get("data").size() > 0) {
                createdSessionId = json.get("data").get(0).get("id").asLong();
                System.out.println("[createSession] 会话ID=" + createdSessionId);
            }
        } catch (Exception e) {
            fail("解析会话列表失败: " + e.getMessage());
        }
        assertNotNull(createdSessionId, "创建会话后应能获取到 sessionId");
    }

    // ==================== 4. 发送消息 ====================

    @Test
    @Order(4)
    @DisplayName("发送聊天消息")
    void testSendMessage() {
        assertNotNull(createdSessionId, "需要先创建会话");

        Map<String, Object> body = Map.of(
                "fromUserId", userId,
                "sessionId", createdSessionId,
                "content", "这是集成测试发送的消息 - " + System.currentTimeMillis()
        );

        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + "/chat/Message/send", authRequest(body), String.class);

        System.out.println("[sendMessage] status=" + resp.getStatusCode() + " body=" + resp.getBody());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("\"success\":true"));
    }

    // ==================== 5. 查询消息 ====================

    @Test
    @Order(5)
    @DisplayName("查询会话消息")
    void testGetMessages() {
        assertNotNull(createdSessionId, "需要先创建会话");

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/chat/Message/messages/" + createdSessionId,
                HttpMethod.GET,
                authRequest(null),
                String.class);

        System.out.println("[getMessages] status=" + resp.getStatusCode() + " body=" + resp.getBody());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        try {
            JsonNode json = objectMapper.readTree(resp.getBody());
            assertTrue(json.get("success").asBoolean());

            if (json.get("data").isArray() && json.get("data").size() > 0) {
                JsonNode firstMsg = json.get("data").get(0);
                // ChatEnvelope 结构验证
                assertTrue(firstMsg.has("msgId"), "消息应有 msgId");
                assertTrue(firstMsg.has("fromUserId"), "消息应有 fromUserId");
                assertTrue(firstMsg.has("payload"), "消息应有 payload");

                sentMsgId = firstMsg.get("msgId").asText();
                System.out.println("[getMessages] 获取到 msgId=" + sentMsgId);
            }
        } catch (Exception e) {
            fail("解析消息列表失败: " + e.getMessage());
        }
    }

    // ==================== 6. 撤回消息 ====================

    @Test
    @Order(6)
    @DisplayName("撤回消息")
    void testRecallMessage() {
        // 如果没有已发送的消息，先发一条
        if (sentMsgId == null) {
            testSendMessage();
            testGetMessages();
        }
        assertNotNull(sentMsgId, "需要先有消息才能撤回");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + "/chat/Message/recall/" + sentMsgId,
                authRequest(null),
                String.class);

        System.out.println("[recallMessage] status=" + resp.getStatusCode() + " body=" + resp.getBody());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("\"success\":true"));

        // 验证撤回后消息内容变化
        ResponseEntity<String> checkResp = restTemplate.exchange(
                baseUrl + "/chat/Message/messages/" + createdSessionId,
                HttpMethod.GET,
                authRequest(null),
                String.class);
        try {
            JsonNode json = objectMapper.readTree(checkResp.getBody());
            if (json.get("data").isArray() && json.get("data").size() > 0) {
                for (JsonNode msg : json.get("data")) {
                    if (sentMsgId.equals(msg.get("msgId").asText())) {
                        String text = msg.get("payload").get("text").asText();
                        assertTrue(text.contains("撤回"), "撤回的消息内容应包含'撤回': " + text);
                        System.out.println("[recallMessage] 撤回后内容: " + text);
                    }
                }
            }
        } catch (Exception e) {
            fail("验证撤回失败: " + e.getMessage());
        }
    }

    // ==================== 7. 软删除消息 ====================

    @Test
    @Order(7)
    @DisplayName("软删除消息")
    void testDeleteMessage() {
        // 再发一条新消息用于测试删除
        Map<String, Object> sendBody = Map.of(
                "fromUserId", userId,
                "sessionId", createdSessionId,
                "content", "待删除的消息 - " + System.currentTimeMillis()
        );
        restTemplate.postForEntity(baseUrl + "/chat/Message/send", authRequest(sendBody), String.class);

        // 查询最新的 msgId
        String deleteTargetMsgId = null;
        ResponseEntity<String> listResp = restTemplate.exchange(
                baseUrl + "/chat/Message/messages/" + createdSessionId,
                HttpMethod.GET,
                authRequest(null),
                String.class);
        try {
            JsonNode json = objectMapper.readTree(listResp.getBody());
            if (json.get("data").isArray()) {
                for (JsonNode msg : json.get("data")) {
                    String curMsgId = msg.get("msgId").asText();
                    if (!curMsgId.equals(sentMsgId)) {
                        deleteTargetMsgId = curMsgId;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            fail("查询待删除消息失败: " + e.getMessage());
        }

        assertNotNull(deleteTargetMsgId, "应有新消息可删除");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + "/chat/Message/delete/" + deleteTargetMsgId,
                authRequest(null),
                String.class);

        System.out.println("[deleteMessage] status=" + resp.getStatusCode() + " body=" + resp.getBody());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("\"success\":true"));

        // 验证删除后消息不再出现在列表中
        ResponseEntity<String> afterDeleteResp = restTemplate.exchange(
                baseUrl + "/chat/Message/messages/" + createdSessionId,
                HttpMethod.GET,
                authRequest(null),
                String.class);
        try {
            JsonNode json = objectMapper.readTree(afterDeleteResp.getBody());
            if (json.get("data").isArray()) {
                for (JsonNode msg : json.get("data")) {
                    assertNotEquals(deleteTargetMsgId, msg.get("msgId").asText(),
                            "已软删除的消息不应出现在列表中");
                }
            }
            System.out.println("[deleteMessage] 删除后消息已正确过滤");
        } catch (Exception e) {
            fail("验证删除失败: " + e.getMessage());
        }
    }

    // ==================== 8. 列出会话 ====================

    @Test
    @Order(8)
    @DisplayName("列出当前用户会话")
    void testListSessions() {
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/chat/session/list",
                HttpMethod.GET,
                authRequest(null),
                String.class);

        System.out.println("[listSessions] status=" + resp.getStatusCode() + " body=" + resp.getBody());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        try {
            JsonNode json = objectMapper.readTree(resp.getBody());
            assertTrue(json.get("success").asBoolean());
            assertTrue(json.get("data").isArray(), "会话列表应为数组");
            System.out.println("[listSessions] 会话数=" + json.get("data").size());
        } catch (Exception e) {
            fail("解析会话列表失败: " + e.getMessage());
        }
    }

    // ==================== 9. 删除会话 ====================

    @Test
    @Order(9)
    @DisplayName("删除会话")
    void testDeleteSession() {
        assertNotNull(createdSessionId, "需要先有会话才能删除");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + "/chat/session/delete/" + createdSessionId,
                authRequest(null),
                String.class);

        System.out.println("[deleteSession] status=" + resp.getStatusCode() + " body=" + resp.getBody());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("\"success\":true"));
    }

    // ==================== 10. 认证失败测试 ====================

    @Test
    @Order(10)
    @DisplayName("无 Token 访问需鉴权接口应返回 401")
    void testAuthRequired() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> request = new HttpEntity<>(null, headers);

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/chat/session/list",
                HttpMethod.GET,
                request,
                String.class);

        System.out.println("[authRequired] status=" + resp.getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "未携带 JWT 应返回 401");
    }
}

package com.uav.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共享测试基建：真实身份获取（R3：认证链路测试必须真实调用注册/登录接口；
 * 业务集成测试从这里取合法 token，禁止用 {@code JwtUtil.generateToken} 自签绕过认证链路）。
 * O2：无 @Test 的工具类。
 *
 * <p>支持两种运行环境：
 * <ul>
 *   <li>{@link #overMockMvc(MockMvc)}：{@code @Transactional} 集成测试（{@link IntegrationTestBase}）。
 *       注册/登录与测试同线程同事务，写入当场可见，回滚即清理，无需额外清理数据。</li>
 *   <li>{@link #overHttp(int)}：{@link RealProtocolTestBase} 等真实 TCP、WebSocket、真库测试。
 *       注册真实提交，靠唯一用户名/DJI ID 隔离。</li>
 * </ul>
 *
 * <p><b>事务语义（R5，迁移者必须按场景选择）</b>：
 * <ul>
 *   <li>{@code @Transactional} 集成测试（{@link IntegrationTestBase}）：注册/登录与测试同线程同事务，
 *       写入当场可见（登录能查到刚注册的行），<b>随测试事务回滚即清理</b>，无需手工删数据。</li>
 *   <li>非事务真实协议测试（{@link RealProtocolTestBase}，如 WebSocket/E2EIT）：没有外层事务，
 *       注册<b>真实提交</b>且不会回滚——隔离必须靠<b>唯一数据</b>（本类生成的唯一用户名 / DJI ID），
 *       不得依赖回滚，也不得用 {@code System.nanoTime()} 兜底。</li>
 * </ul>
 *
 * <p>两种环境下 {@link Account#token()} 都是服务端真实签发的 accessToken，可直接用于：
 * REST 的 {@code Authorization: Bearer <token>}、WebSocket 握手 {@code ws://host:port/ws/...?token=<token>}
 * （{@code WsAuthHandshakeInterceptor} 支持 query 令牌；{@code /ws/drone} 还要求 role=1 且
 * {@code deviceId} 已绑定到该飞手，故需用带同一 djiId 注册的 {@link #registerRider(String, String)}），
 * 以及 {@code @RequireDrone} 端点（要求 {@code riderUavRepository.existsByUserId}）。
 *
 * <p><b>限流身份隔离</b>：{@code RateLimiterAspect} 的 key 退化为 X-Forwarded-For/remoteAddr 时，
 * MockMvc 与真实 TCP 下客户端地址恒定，第 4 次注册、第 6 次登录起必然 429。这里为**每次调用**
 * 分配唯一客户端身份（{@link UniqueNames#clientIp()}），既不改生产代码也不关闭限流。
 */
public final class TestAccounts {

    /** 测试账号统一密码（BCrypt 输入长度受限，保持简短）。 */
    public static final String DEFAULT_PASSWORD = "It-Passw0rd!23";

    /** {@code AdminSeedInitializer} 播种的默认管理员。 */
    public static final String ADMIN_NAME = "admin";

    /** {@code AdminSeedInitializer} 播种的默认管理员密码。 */
    public static final String ADMIN_PASSWORD = "123456";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Transport transport;

    private TestAccounts(Transport transport) {
        this.transport = transport;
    }

    /** 进程内 MockMvc 传输：用于 {@code @Transactional} 集成测试。 */
    public static TestAccounts overMockMvc(MockMvc mockMvc) {
        return new TestAccounts(new MockMvcTransport(mockMvc));
    }

    /** 真实 TCP 传输（JDK HttpClient，无额外依赖）：用于非事务的真实协议测试。 */
    public static TestAccounts overHttp(int port) {
        return new TestAccounts(new HttpTransport(port));
    }

    // ---------- 普通用户 ----------

    /** 注册一个唯一用户名的新用户并登录，返回可用 token。 */
    public Account registerUser() {
        return registerUser(UniqueNames.userName());
    }

    /** 用指定用户名注册并登录；注册或登录失败时带上响应体快速失败。 */
    public Account registerUser(String userName) {
        postExpectOk("/user/register", Map.of("userName", userName, "password", DEFAULT_PASSWORD));
        return login(userName, DEFAULT_PASSWORD);
    }

    /** 真实调用 {@code /user/login} 取 token（用户名需已注册）。 */
    public Account login(String userName, String password) {
        JsonNode json = postExpectOk("/user/login", Map.of("userName", userName, "password", password));
        JsonNode data = json.path("data");
        String token = data.path("token").asText();
        assertThat(token).as("/user/login 应返回 token，响应=%s", json).isNotBlank();
        return new Account(claims(token).path("userId").asLong(), userName, password,
                claims(token).path("role").asInt(), null, token, data.path("refreshToken").asText());
    }

    // ---------- 飞手 ----------

    /** 注册一个唯一用户名、已绑定唯一无人机的飞手（{@code /rider/register} 直接返回 token）。 */
    public Account registerRider() {
        return registerRider(UniqueNames.userName("rider"), UniqueNames.djiId());
    }

    /**
     * 用指定用户名与 DJI ID 注册飞手；{@code djiId} 为 {@code null}/空表示不绑定无人机，
     * 该飞手随后访问 {@code @RequireDrone} 端点应被 403 拒绝。
     */
    public Account registerRider(String userName, String djiId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", userName);
        body.put("password", DEFAULT_PASSWORD);
        body.put("djiId", djiId);
        JsonNode json = postExpectOk("/rider/register", body);
        String token = json.path("data").path("token").asText();
        assertThat(token).as("/rider/register 应直接返回 token，响应=%s", json).isNotBlank();
        return new Account(claims(token).path("userId").asLong(), userName, DEFAULT_PASSWORD,
                claims(token).path("role").asInt(), djiId, token, json.path("data").path("refreshToken").asText());
    }

    // ---------- 管理员 ----------

    /** 用 {@code AdminSeedInitializer} 播种的 admin/123456 登录并取 token。 */
    public AdminAccount adminLogin() {
        JsonNode json = postExpectOk("/admin/login", Map.of("name", ADMIN_NAME, "password", ADMIN_PASSWORD));
        JsonNode data = json.path("data");
        String token = data.path("token").asText();
        assertThat(token).as("/admin/login 应返回 token，响应=%s", json).isNotBlank();
        return new AdminAccount(data.path("admin").path("id").asLong(), ADMIN_NAME, ADMIN_PASSWORD, token);
    }

    // ---------- 低层调用（错误路径/限流用例自行断言） ----------

    /**
     * 低层 POST：分配唯一客户端身份、不做任何状态断言，供 401/429 等错误路径用例断言。
     * 认证端点均为 {@code @SkipJwt}，无需 Authorization。
     */
    public Response postJson(String path, Object body) {
        return transport.post(path, serialize(body), UniqueNames.clientIp(), null);
    }

    // ---------- JWT claims 读取 ----------

    /** 读取真实签发 token 的 claims（不校验签名；token 由本服务签发，仅用于取 userId/role）。 */
    public static JsonNode claims(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("不是合法 JWT: " + token);
        }
        String payload = parts[1];
        int padding = payload.length() % 4;
        if (padding == 2) {
            payload += "==";
        } else if (padding == 3) {
            payload += "=";
        }
        try {
            return MAPPER.readTree(Base64.getUrlDecoder().decode(payload));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 JWT payload", e);
        }
    }

    private JsonNode postExpectOk(String path, Object body) {
        Response response = transport.post(path, serialize(body), UniqueNames.clientIp(), null);
        assertThat(response.status())
                .as("POST %s 期望 200，实际 %d，响应体=%s", path, response.status(), response.body())
                .isEqualTo(200);
        assertThat(response.success())
                .as("POST %s 期望 success=true，响应体=%s", path, response.body())
                .isTrue();
        return response.json();
    }

    private static String serialize(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("测试请求体序列化失败: " + body, e);
        }
    }

    static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应体不是合法 JSON: " + body, e);
        }
    }

    // ---------- 结果模型 ----------

    /** 一次真实身份获取的结果；{@code token} 由服务端签发，可直接用于 Authorization。 */
    public record Account(Long id, String userName, String password, int role, String djiId,
                          String token, String refreshToken) {

        public String authorization() {
            return "Bearer " + token;
        }
    }

    /** 管理员登录结果。 */
    public record AdminAccount(Long id, String name, String password, String token) {

        public String authorization() {
            return "Bearer " + token;
        }
    }

    /** 原始响应，供错误路径用例断言状态码与响应体。 */
    public record Response(int status, String body) {

        public JsonNode json() {
            return parse(body);
        }

        public boolean success() {
            return json().path("success").asBoolean(false);
        }
    }

    private interface Transport {
        Response post(String path, String jsonBody, String clientIp, String authorization);
    }

    private record MockMvcTransport(MockMvc mockMvc) implements Transport {

        @Override
        public Response post(String path, String jsonBody, String clientIp, String authorization) {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonBody)
                    .header("X-Forwarded-For", clientIp);
            if (authorization != null) {
                request = request.header("Authorization", authorization);
            }
            try {
                var result = mockMvc.perform(request).andReturn();
                return new Response(result.getResponse().getStatus(), result.getResponse().getContentAsString());
            } catch (Exception e) {
                throw new IllegalStateException("MockMvc POST " + path + " 执行失败", e);
            }
        }
    }

    private static final class HttpTransport implements Transport {

        private final int port;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        private HttpTransport(int port) {
            this.port = port;
        }

        @Override
        public Response post(String path, String jsonBody, String clientIp, String authorization) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Forwarded-For", clientIp)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            try {
                HttpResponse<String> response = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Response(response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("HTTP POST " + path + " 被中断", e);
            } catch (Exception e) {
                throw new IllegalStateException("HTTP POST " + path + " 执行失败", e);
            }
        }
    }
}

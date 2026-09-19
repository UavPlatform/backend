package com.uav.security;

import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实身份链路集成测试（R3 / P0）：用真实注册/登录接口覆盖
 * {@code /user/register}、{@code /user/login}、{@code /rider/register} 与 {@code /admin/login}。
 *
 * <p>这是进程内 MockMvc 集成测试（O6）：不启动真实 TCP，也不自称端到端；请求经 MockMvc 在测试线程内
 * 同步调用 DispatcherServlet，因此继承 {@link IntegrationTestBase} 的 {@code @Transactional} 回滚隔离。
 *
 * <p>本类同时是 {@link TestAccounts} 的验收：证明共享基建取到的是**服务端真实签发**的合法 token，
 * 且通过每次调用独立的客户端身份避开限流，而非修改生产代码或关闭限流。
 */
@DisplayName("真实身份链路（MockMvc 集成）：注册 / 登录 / 飞手注册 / 管理员登录")
class AccountAuthIT extends IntegrationTestBase {

    @Test
    @DisplayName("普通用户注册返回 id 与用户名，登录返回可用于受保护端点的真实 token")
    void userRegisterAndLoginProduceUsableToken() throws Exception {
        String userName = UniqueNames.userName("auth");
        String password = TestAccounts.DEFAULT_PASSWORD;

        TestAccounts.Response registered = accounts().postJson("/user/register",
                Map.of("userName", userName, "password", password));
        assertThat(registered.status()).as("注册响应体=%s", registered.body()).isEqualTo(200);
        assertThat(registered.json().path("success").asBoolean()).isTrue();
        assertThat(registered.json().path("data").path("userName").asText()).isEqualTo(userName);
        long registeredId = registered.json().path("data").path("id").asLong();
        assertThat(registeredId).isPositive();

        TestAccounts.Account account = accounts().login(userName, password);
        assertThat(account.token()).isNotBlank();
        assertThat(account.refreshToken()).isNotBlank();
        assertThat(account.id()).isEqualTo(registeredId);
        assertThat(account.role()).isZero();

        // 认证链路真实闭环：注册/登录取到的 token 能通过 JwtInterceptor 访问受保护端点
        mockMvc.perform(get("/user/records").header("Authorization", account.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("密码错误的登录被拒绝（401，success=false）")
    void loginWithWrongPasswordIsRejected() {
        TestAccounts.Account account = accounts().registerUser();

        TestAccounts.Response rejected = accounts().postJson("/user/login",
                Map.of("userName", account.userName(), "password", "definitely-not-the-password"));

        assertThat(rejected.status()).as("响应体=%s", rejected.body()).isEqualTo(401);
        assertThat(rejected.json().path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("飞手注册直接返回 token，绑定无人机后可通过 @RequireDrone 门槛")
    void riderRegisterBindsDroneAndPassesRequireDroneGate() throws Exception {
        TestAccounts.Account rider = accounts().registerRider();
        assertThat(rider.role()).isEqualTo(1);
        assertThat(rider.djiId()).isNotBlank();

        // /rider/accept 标注 @RequireDrone：未绑定 → 403，未认证 → 401，角色不符 → 403；
        // 返回 404（任务不存在）唯一地证明身份、角色与无人机绑定三道门槛全部通过。
        mockMvc.perform(post("/rider/accept")
                        .param("taskNum", UniqueNames.unique("TN"))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未绑定无人机的飞手访问 @RequireDrone 端点被 403 拒绝")
    void riderWithoutDroneIsRejectedByRequireDroneGate() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rider"), null);

        mockMvc.perform(post("/rider/accept")
                        .param("taskNum", UniqueNames.unique("TN"))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("请先绑定至少一台无人机"));
    }

    @Test
    @DisplayName("AdminSeedInitializer 播种的 admin/123456 可登录并访问管理员端点")
    void seededAdminCanLoginAndUseAdminToken() throws Exception {
        TestAccounts.AdminAccount admin = accounts().adminLogin();
        assertThat(admin.token()).isNotBlank();

        mockMvc.perform(get("/admin/uav").header("Authorization", admin.authorization()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("连续获取 6 个以上普通用户/飞手身份不触发 429（限流桶按客户端身份隔离）")
    void moreThanSixIdentitiesWithoutRateLimit() {
        List<TestAccounts.Account> obtained = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            obtained.add(accounts().registerUser());
        }
        for (int i = 0; i < 3; i++) {
            obtained.add(accounts().registerRider());
        }

        // 任何一次调用返回 429 都会在 TestAccounts 内部断言失败；这里再验证身份确实各不相同。
        assertThat(obtained).hasSize(7);
        assertThat(obtained).allSatisfy(account -> assertThat(account.token()).isNotBlank());
        assertThat(obtained).extracting(TestAccounts.Account::id).doesNotHaveDuplicates();
        assertThat(obtained).extracting(TestAccounts.Account::userName).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("限流未被关闭：同一客户端身份连续 4 次注册，第 4 次返回 429")
    void sharedClientIdentityStillHitsRateLimit() throws Exception {
        // 专用客户端身份：限流窗口按 方法+身份 计数，不会污染其它用例的桶。
        String clientIp = UniqueNames.clientIp();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/user/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Forwarded-For", clientIp)
                            .content(registerBody(UniqueNames.userName("rl"))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", clientIp)
                        .content(registerBody(UniqueNames.userName("rl"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    private static String registerBody(String userName) {
        return "{\"userName\":\"" + userName + "\",\"password\":\"" + TestAccounts.DEFAULT_PASSWORD + "\"}";
    }
}

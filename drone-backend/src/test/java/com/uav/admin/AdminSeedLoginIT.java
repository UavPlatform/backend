package com.uav.admin;

import com.uav.admin.config.AdminSeedInitializer;
import com.uav.admin.mapper.AdminRepository;
import com.uav.admin.pojo.entity.Admin;
import com.uav.server.util.PasswordUtil;
import com.uav.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 种子管理员防护测试（P1-13）：种子管理员可正常登录（不再因 data.sql 明文密码必 500）。
 * 覆盖：登录成功返回 token、token 具备管理员角色（可访问 /admin/uav）、密码错误 401、
 * 播种幂等、历史明文密码启动时原地修复。
 *
 * <p>层次与驱动（O6/R2/R4）：进程内 MockMvc 集成测试，继承 {@link IntegrationTestBase}
 * （MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}）。
 *
 * <p>隔离方式（R5）：{@link AdminSeedInitializer} 是 {@code ApplicationRunner}，在上下文启动时**已真实提交**
 * 种子管理员行，这部分不在测试事务内、也不假设可回滚；而本类用例对 {@code admin} 行的删除/改写
 * （明文修复、幂等重播）都发生在测试线程的 {@code @Transactional} 事务中，用例结束即整体回滚到
 * 启动播种后的状态。因此原先「{@code @AfterEach} 手工重播 {@code seed()} 恢复共享 H2」的做法不再需要，
 * 已删除手工清理，隔离由事务回滚保证。
 *
 * <p>必要适配（为在事务内保持原语义，非重写断言）：{@code adminRepository.deleteAll()} 之后补一次
 * {@code adminRepository.flush()}。在 {@code @Transactional} 下 Hibernate 把 INSERT 排在 DELETE 之前，
 * 不先落库删除就重播同一 {@code name} 会撞 {@code admin.name} 唯一索引（23505-240）；flush 只调整
 * SQL 发出顺序。未迁移前每条写操作各自提交，因此从未暴露该顺序问题，故这不属于「迁移引入的缺陷」，
 * 而是「迁入事务后保持原语义」的必要适配。断言语义不变。
 */
class AdminSeedLoginIT extends IntegrationTestBase {

    @Autowired
    AdminRepository adminRepository;

    @Autowired
    AdminSeedInitializer seedInitializer;

    @Test
    @DisplayName("种子管理员 admin/123456 登录成功并返回 token")
    void seedAdminLoginReturnsToken() throws Exception {
        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.admin.name").value("admin"));
    }

    @Test
    @DisplayName("种子管理员 token 具备管理员角色（可访问 /admin/uav）")
    void seedAdminTokenHasAdminRole() throws Exception {
        String body = mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = com.jayway.jsonpath.JsonPath.read(body, "$.data.token");

        mockMvc.perform(get("/admin/uav").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("密码错误登录被拒（401）")
    void seedAdminWrongPasswordRejected() throws Exception {
        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("播种幂等：重复 seed 不改写已存在的 BCrypt 密码")
    void seedingIsIdempotent() {
        Admin admin = adminRepository.findByName("admin").orElseThrow();
        String savedHash = admin.getPassword();
        assertThat(savedHash).startsWith("$2");

        seedInitializer.seed();
        seedInitializer.seed();

        Admin after = adminRepository.findByName("admin").orElseThrow();
        assertThat(after.getPassword()).isEqualTo(savedHash);
    }

    @Test
    @DisplayName("历史明文密码在播种时原地修复为 BCrypt，登录恢复可用")
    void legacyPlaintextPasswordRepaired() {
        // 模拟存量部署：data.sql 时代插入的明文密码行
        adminRepository.deleteAll();
        // R5 事务隔离下必须显式 flush：Hibernate 的动作队列先执行 INSERT 再执行 DELETE，
        // 否则同一事务内「删旧 admin 行 + 插新 admin 行」会先撞 name 唯一索引。
        adminRepository.flush();
        Admin legacy = new Admin();
        legacy.setName("admin");
        legacy.setPassword("123456");   // 明文
        legacy.setPhoneNumber("13800138000");
        adminRepository.save(legacy);

        seedInitializer.seed();

        Admin repaired = adminRepository.findByName("admin").orElseThrow();
        assertThat(repaired.getPassword()).startsWith("$2");
        assertThat(PasswordUtil.matches("123456", repaired.getPassword())).isTrue();
    }
}

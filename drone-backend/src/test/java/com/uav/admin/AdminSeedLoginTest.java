package com.uav.admin;

import com.uav.admin.config.AdminSeedInitializer;
import com.uav.admin.mapper.AdminRepository;
import com.uav.admin.pojo.entity.Admin;
import com.uav.server.util.PasswordUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-13 防护测试：种子管理员可正常登录（不再因 data.sql 明文密码必 500）。
 * 覆盖：登录成功返回 token、token 具备管理员角色（可访问 /admin/uav）、
 * 密码错误 401、播种幂等、历史明文密码启动时原地修复。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminSeedLoginTest {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    AdminRepository adminRepository;

    @Autowired
    AdminSeedInitializer seedInitializer;

    MockMvc mockMvc;

    private String savedHash;

    private String savedPhoneNumber;

    @AfterEach
    void restoreSeedAdmin() {
        // 恢复共享 H2 中的种子行，保证其余用例与其它测试类不受影响
        seedInitializer.seed();
    }

    @Test
    @DisplayName("种子管理员 admin/123456 登录成功并返回 token")
    void seedAdminLoginReturnsToken() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

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
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

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
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("播种幂等：重复 seed 不改写已存在的 BCrypt 密码")
    void seedingIsIdempotent() {
        Admin admin = adminRepository.findByName("admin").orElseThrow();
        savedHash = admin.getPassword();
        savedPhoneNumber = admin.getPhoneNumber();
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

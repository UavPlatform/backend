package com.uav.attachment;

import com.uav.attachment.service.AttachmentService;
import com.uav.server.util.JwtUtil;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-9b：对象存储未配置（MINIO_* 为空，默认）→ 交付物接口 503，不抛构建异常。
 * 复用共享基础上下文（未注入 minio 属性），验证降级语义。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AttachmentUnconfiguredTest {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    com.uav.attachment.service.MinioStorageService minioStorageService;

    @Test
    @DisplayName("MinIOStorageService 未配置时 isConfigured=false")
    void storageReportsUnconfigured() {
        // 共享上下文未注入 minio.* 属性 → 默认未配置
        assertThat(minioStorageService.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("未配置时上传凭证接口 → 503")
    void uploadUrlReturns503WhenUnconfigured() throws Exception {
        User user = newUser(0);
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        mockMvc.perform(post("/tasks/unknown-task/attachments/upload-url")
                        .param("fileName", "x.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", "1000")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                user.getId(), user.getUserName(), 0)))
                .andExpect(status().isServiceUnavailable());
    }

    private User newUser(int role) {
        User user = new User();
        user.setUserName("unconf" + role + "-" + java.util.UUID.randomUUID()
                .toString().substring(0, 6));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }
}

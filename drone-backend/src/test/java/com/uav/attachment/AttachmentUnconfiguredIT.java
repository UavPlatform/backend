package com.uav.attachment;

import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对象存储未配置（MINIO_* 为空，默认）→ 交付物接口 503，不抛构建异常。
 *
 * <p>层次与驱动（O6/R2/R4）：进程内 MockMvc 集成测试，继承 {@link IntegrationTestBase}
 * （MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}），复用未注入 minio 属性的共享上下文。
 *
 * <p>身份来源（R3）：使用 {@link TestAccounts} 真实注册得到的合法 token，不再用
 * {@code JwtUtil.generateToken} 自签；造数走共享工厂（R7），事务回滚即隔离（R5）。
 */
class AttachmentUnconfiguredIT extends IntegrationTestBase {

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
        TestAccounts.Account user = accounts().registerUser();

        mockMvc.perform(post("/tasks/unknown-task/attachments/upload-url")
                        .param("fileName", "x.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", "1000")
                        .header("Authorization", user.authorization()))
                .andExpect(status().isServiceUnavailable());
    }
}

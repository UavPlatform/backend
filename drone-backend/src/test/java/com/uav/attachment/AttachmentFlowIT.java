package com.uav.attachment;

import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.mapper.TaskAttachmentRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交付物附件链路集成测试（presigned URL 模式，无需真实 MinIO 在线——签名本地计算）：
 * 飞手上传凭证、越权拒绝、类型/大小限制、附件列表与下载凭证。
 *
 * <p>层次与驱动（O6/R2/R4）：进程内 MockMvc 集成测试，继承 {@link IntegrationTestBase}
 * （MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}）；<b>不是</b>端到端测试——
 * 不发真实 HTTP、不启动真实容器，请求经 MockMvc 在测试线程内同步调用 DispatcherServlet。
 *
 * <p>本类显式声明 {@code @SpringBootTest(properties = ...)} 以注入 minio 配置并改用独立内存库
 * （避免与共享上下文的 create-drop 互相清库），因此会单独缓存一份上下文；其余注解与隔离方式
 * 仍来自 {@link IntegrationTestBase}。
 *
 * <p>身份来源（R3）：所有者/飞手/第三方身份一律取自 {@link TestAccounts} 的真实注册/登录接口，
 * 不再用 {@code JwtUtil.generateToken} 自签；造数走共享工厂（R7），ThreadLocal 由基类清理（R8），
 * 事务回滚即隔离（R5）。
 */
@SpringBootTest(properties = {
        "minio.endpoint=http://localhost:9000",
        "minio.access-key=minioadmin",
        "minio.secret-key=minioadmin",
        "minio.bucket=drone-test",
        // 独立内存库：避免与共享上下文的 create-drop 互相清库
        "spring.datasource.url=jdbc:h2:mem:drone_attachment_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"
})
class AttachmentFlowIT extends IntegrationTestBase {

    @Autowired
    TaskAttachmentRepository taskAttachmentRepository;

    @Autowired
    TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    TaskService taskService;

    @Test
    @DisplayName("飞手获取上传凭证：presigned PUT + 附件行登记")
    void riderGetsUploadUrl() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("att-rider"), null);
        Task currentTask = seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "ortho.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", "1048576")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectKey").isNotEmpty())
                .andExpect(jsonPath("$.data.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.method").value("PUT"));

        var rows = taskAttachmentRepository.findByTaskNumOrderByCreateTimeAsc(currentTask.getTaskNum());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getContentType()).isEqualTo("image/jpeg");
        assertThat(rows.get(0).getSizeBytes()).isEqualTo(1048576L);
        assertThat(rows.get(0).getUploaderId()).isEqualTo(rider.id());
    }

    @Test
    @DisplayName("授权放宽（t47）：任务所有者也可请求上传凭证 → 200")
    void ownerCanUploadAfterWidening() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("att-rider"), null);
        Task currentTask = seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "owner-note.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", "1000")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadUrl").isNotEmpty());
    }

    @Test
    @DisplayName("越权：无关第三方用户查看附件列表 → 403")
    void unrelatedUserCannotList() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("att-rider"), null);
        Task currentTask = seedTaskAndAssignment(owner, rider);
        TestAccounts.Account stranger = accounts().registerUser();

        mockMvc.perform(get("/tasks/" + currentTask.getTaskNum() + "/attachments")
                        .header("Authorization", stranger.authorization()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("类型限制：video/x-msvideo → 400")
    void disallowedContentTypeRejected() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("att-rider"), null);
        Task currentTask = seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "clip.avi")
                        .param("contentType", "video/x-msvideo")
                        .param("sizeBytes", "1000")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("仅支持")));
    }

    @Test
    @DisplayName("大小限制：图片超 20MB → 400；视频 400MB 内 → 通过")
    void sizeLimitEnforced() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("att-rider"), null);
        Task currentTask = seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "big.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", String.valueOf(21L * 1024 * 1024))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("20 MB")));

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "clip.mp4")
                        .param("contentType", "video/mp4")
                        .param("sizeBytes", String.valueOf(400L * 1024 * 1024))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("列表与下载凭证：所有者与飞手可看，含 presigned GET")
    void listAndDownloadUrlForOwnerAndRider() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("att-rider"), null);
        Task currentTask = seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "ortho.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", "1024")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk());
        String objectKey = taskAttachmentRepository.findByTaskNumOrderByCreateTimeAsc(currentTask.getTaskNum())
                .get(0).getObjectKey();

        List<TestAccounts.Account> viewers = List.of(owner, rider);
        for (TestAccounts.Account viewer : viewers) {
            mockMvc.perform(get("/tasks/" + currentTask.getTaskNum() + "/attachments")
                            .header("Authorization", viewer.authorization()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].fileName").value("ortho.jpg"))
                    .andExpect(jsonPath("$.data[0].downloadUrl").isNotEmpty());

            mockMvc.perform(get("/tasks/" + currentTask.getTaskNum() + "/attachments/download-url")
                            .param("objectKey", objectKey)
                            .header("Authorization", viewer.authorization()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.downloadUrl").isNotEmpty());
        }
    }

    @Test
    @DisplayName("下载凭证：objectKey 不属于该任务 → 404")
    void downloadUrlForForeignObjectKeyNotFound() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("att-rider"), null);
        Task currentTask = seedTaskAndAssignment(owner, rider);

        mockMvc.perform(get("/tasks/" + currentTask.getTaskNum() + "/attachments/download-url")
                        .param("objectKey", "foreign-key")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    /** 创建任务并直接登记接单（交付物授权只需 assignment 行；支付链路由 t21/t22 覆盖）。 */
    private Task seedTaskAndAssignment(TestAccounts.Account owner, TestAccounts.Account rider) {
        UserContext.setUser(owner.id(), owner.userName(), 0);
        Task task = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("att-task")));

        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(task.getId());
        assignment.setRiderId(rider.id());
        assignment.setAcceptTime(LocalDateTime.now());
        taskAssignmentRepository.save(assignment);
        return task;
    }
}

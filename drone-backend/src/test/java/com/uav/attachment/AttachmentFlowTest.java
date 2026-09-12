package com.uav.attachment;

import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.task.mapper.TaskAttachmentRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.dto.WaypointDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.service.TaskService;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-9b 端到端测试（presigned URL 模式，无需真实 MinIO 在线——签名本地计算）：
 * 飞手上传凭证、越权拒绝、类型/大小限制、附件列表与下载凭证。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "minio.endpoint=http://localhost:9000",
        "minio.access-key=minioadmin",
        "minio.secret-key=minioadmin",
        "minio.bucket=drone-test",
        // 独立内存库：避免与共享上下文的 create-drop 互相清库
        "spring.datasource.url=jdbc:h2:mem:drone_attachment_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class AttachmentFlowTest {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TaskAttachmentRepository taskAttachmentRepository;

    @Autowired
    TaskService taskService;

    MockMvc mockMvc;

    private long rid;

    private Task currentTask;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rid = System.nanoTime();
    }

    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("飞手获取上传凭证：presigned PUT + 附件行登记")
    void riderGetsUploadUrl() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "ortho.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", "1048576")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                rider.getId(), rider.getUserName(), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectKey").isNotEmpty())
                .andExpect(jsonPath("$.data.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.method").value("PUT"));

        var rows = taskAttachmentRepository.findByTaskNumOrderByCreateTimeAsc(currentTask.getTaskNum());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getContentType()).isEqualTo("image/jpeg");
        assertThat(rows.get(0).getSizeBytes()).isEqualTo(1048576L);
        assertThat(rows.get(0).getUploaderId()).isEqualTo(rider.getId());
    }

    @Test
    @DisplayName("授权放宽（t47）：任务所有者也可请求上传凭证 → 200")
    void ownerCanUploadAfterWidening() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "owner-note.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", "1000")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                owner.getId(), owner.getUserName(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadUrl").isNotEmpty());
    }

    @Test
    @DisplayName("越权：无关第三方用户查看附件列表 → 403")
    void unrelatedUserCannotList() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        seedTaskAndAssignment(owner, rider);
        User stranger = newUser(0);

        mockMvc.perform(get("/tasks/" + currentTask.getTaskNum() + "/attachments")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                stranger.getId(), stranger.getUserName(), 0)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("类型限制：video/x-msvideo → 400")
    void disallowedContentTypeRejected() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "clip.avi")
                        .param("contentType", "video/x-msvideo")
                        .param("sizeBytes", "1000")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                rider.getId(), rider.getUserName(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("仅支持")));
    }

    @Test
    @DisplayName("大小限制：图片超 20MB → 400；视频 400MB 内 → 通过")
    void sizeLimitEnforced() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        seedTaskAndAssignment(owner, rider);

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "big.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", String.valueOf(21L * 1024 * 1024))
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                rider.getId(), rider.getUserName(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("20 MB")));

        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "clip.mp4")
                        .param("contentType", "video/mp4")
                        .param("sizeBytes", String.valueOf(400L * 1024 * 1024))
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                rider.getId(), rider.getUserName(), 1)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("列表与下载凭证：所有者与飞手可看，含 presigned GET")
    void listAndDownloadUrlForOwnerAndRider() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        seedTaskAndAssignment(owner, rider);
        String riderToken = "Bearer " + jwtUtil.generateToken(
                rider.getId(), rider.getUserName(), 1);
        mockMvc.perform(post("/tasks/" + currentTask.getTaskNum() + "/attachments/upload-url")
                        .param("fileName", "ortho.jpg")
                        .param("contentType", "image/jpeg")
                        .param("sizeBytes", "1024")
                        .header("Authorization", riderToken))
                .andExpect(status().isOk());
        String objectKey = taskAttachmentRepository.findByTaskNumOrderByCreateTimeAsc(currentTask.getTaskNum())
                .get(0).getObjectKey();

        List<User> viewers = List.of(owner, rider);
        for (User viewer : viewers) {
            String token = "Bearer " + jwtUtil.generateToken(
                    viewer.getId(), viewer.getUserName(), viewer.getRole());
            mockMvc.perform(get("/tasks/" + currentTask.getTaskNum() + "/attachments")
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].fileName").value("ortho.jpg"))
                    .andExpect(jsonPath("$.data[0].downloadUrl").isNotEmpty());

            mockMvc.perform(get("/tasks/" + currentTask.getTaskNum() + "/attachments/download-url")
                            .param("objectKey", objectKey)
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.downloadUrl").isNotEmpty());
        }
    }

    @Test
    @DisplayName("下载凭证：objectKey 不属于该任务 → 404")
    void downloadUrlForForeignObjectKeyNotFound() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        seedTaskAndAssignment(owner, rider);

        mockMvc.perform(get("/tasks/" + currentTask.getTaskNum() + "/attachments/download-url")
                        .param("objectKey", "foreign-key")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                rider.getId(), rider.getUserName(), 1)))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private User newUser(int role) {
        User user = new User();
        user.setUserName("att" + rid + "-" + role + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 6));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    /** 创建任务并直接登记接单（交付物授权只需 assignment 行；支付链路由 t21/t22 覆盖）。 */
    private void seedTaskAndAssignment(User owner, User rider) {
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        TaskDto dto = new TaskDto();
        dto.setTaskName("att-task-" + rid);
        dto.setType(com.uav.server.enums.TaskType.SURVEY);
        WaypointDto a = new WaypointDto();
        a.setOrderIndex(0);
        a.setLongitude(121.0);
        a.setLatitude(31.0);
        a.setAltitude(100.0);
        WaypointDto b = new WaypointDto();
        b.setOrderIndex(1);
        b.setLongitude(121.01);
        b.setLatitude(31.0);
        b.setAltitude(100.0);
        dto.setWaypoints(List.of(a, b));
        currentTask = taskService.createTask(dto);

        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(currentTask.getId());
        assignment.setRiderId(rider.getId());
        assignment.setAcceptTime(java.time.LocalDateTime.now());
        wac.getBean(com.uav.task.mapper.TaskAssignmentRepository.class).save(assignment);
    }
}

package com.uav.task;

import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
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
 * 契约测试（APP P0-4）：任务时间字段全链路。
 * /task/create 接收 "yyyy-MM-dd HH:mm:ss" 格式 taskTime（Flutter 发布页线上格式，空格分隔）→
 * 落库 → /task/list 与 /task/detail 原格式返回；taskTime 可选（缺省 null 不报错）。
 * MockMvc 集成测试（R9/O4）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + {@code @AutoConfigureMockMvc}），
 * 删除无实际用途的 {@code RANDOM_PORT} 与 {@code @LocalServerPort} 字段，不再手工
 * {@code MockMvcBuilders.webAppContextSetup}；R3：token 由 {@link TestAccounts} 真实登录取得。
 */
class TaskTimeContractIT extends IntegrationTestBase {

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("发布携带 taskTime 落库，detail/list 原格式返回")
    void taskTimeRoundTrip() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        String token = user.authorization();
        String createBody = "{"
                + "\"taskName\":\"tt-contract\","
                + "\"type\":\"SURVEY\","
                + "\"description\":\"契约测试\","
                + "\"taskTime\":\"2026-09-12 14:30:00\","
                + "\"waypoints\":[{\"orderIndex\":0,\"longitude\":121.0,\"latitude\":31.0,\"altitude\":100},"
                + "{\"orderIndex\":1,\"longitude\":121.01,\"latitude\":31.0,\"altitude\":100}]"
                + "}";

        String createResponse = mockMvc.perform(post("/task/create")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskTime").value("2026-09-12 14:30:00"))
                .andReturn().getResponse().getContentAsString();

        String taskNum = com.jayway.jsonpath.JsonPath.read(createResponse, "$.data.taskNum");

        // detail 回读（App 端 P0-4 的核心诉求：发布时间能查回来）
        mockMvc.perform(get("/task/detail")
                        .param("taskNum", taskNum)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskTime").value("2026-09-12 14:30:00"));

        // list 同样返回
        mockMvc.perform(get("/task/list")
                        .param("page", "0")
                        .param("size", "10")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks[0].taskTime").value("2026-09-12 14:30:00"));
    }

    @Test
    @DisplayName("taskTime 可选：不传时正常创建（null 落库）")
    void taskTimeOptional() {
        TestAccounts.Account user = accounts().registerUser();
        UserContext.setUser(user.id(), user.userName(), user.role());

        TaskDto dto = fixtures.twoWaypointTask();
        dto.setTaskTime(null);

        Task saved = taskService.createTask(dto);
        assertThat(saved.getTaskTime()).isNull();
    }
}

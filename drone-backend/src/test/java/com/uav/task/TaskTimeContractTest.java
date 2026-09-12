package com.uav.task;

import com.uav.server.enums.TaskType;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.dto.WaypointDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
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
 * 1A-7a 契约测试（APP P0-4）：任务时间字段全链路。
 * /task/create 接收 "yyyy-MM-dd HH:mm:ss" 格式 taskTime（Flutter 发布页线上格式，空格分隔）→
 * 落库 → /task/list 与 /task/detail 原格式返回；taskTime 可选（缺省 null 不报错）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TaskTimeContractTest {

    @LocalServerPort
    int port;

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TaskService taskService;

    MockMvc mockMvc;

    private long rid;

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
    @DisplayName("发布携带 taskTime 落库，detail/list 原格式返回")
    void taskTimeRoundTrip() throws Exception {
        User user = newUser();
        String token = bearer(user);
        String createBody = "{"
                + "\"taskName\":\"tt-" + rid + "\","
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
        User user = newUser();
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = new TaskDto();
        dto.setTaskName("no-time-" + rid);
        dto.setType(TaskType.SURVEY);
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

        Task saved = taskService.createTask(dto);
        assertThat(saved.getTaskTime()).isNull();
    }

    // ---------- helpers ----------

    private User newUser() {
        User user = new User();
        user.setUserName("tt" + rid);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(0);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(user.getId(), user.getUserName(), user.getRole());
    }
}

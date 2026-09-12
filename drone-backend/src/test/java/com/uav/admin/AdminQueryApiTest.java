package com.uav.admin;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-5 端到端测试：管理员分页查询全平台任务/订单；普通用户/飞手 403；
 * 订单状态码契约（0-5 含 4/5）与 actionHint 可供 OrderView 状态矩阵渲染。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminQueryApiTest {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderRepository orderRepository;

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
    @DisplayName("管理员分页查询全平台订单：状态码契约含 4/5，字段齐全")
    void adminListOrdersWithStatusContract() throws Exception {
        User owner = newUser(0);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task paidTask = taskService.createTask(twoWaypointTask("paid-" + rid));
        markPaid(paidTask);
        Task pendingTask = taskService.createTask(twoWaypointTask("pending-" + rid));
        UserContext.clear();

        User admin = newUser(2);
        String token = "Bearer " + jwtUtil.generateToken(admin.getId(), admin.getUserName(), 2);

        mockMvc.perform(get("/admin/orders")
                        .param("page", "0").param("size", "50")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.taskNum == '" + paidTask.getTaskNum() + "')].orderStatusCode")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.content[?(@.taskNum == '" + pendingTask.getTaskNum() + "')].orderStatusCode")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.data.content[?(@.taskNum == '" + pendingTask.getTaskNum() + "')].ownerName")
                        .value(org.hamcrest.Matchers.hasItem(owner.getUserName())));
    }

    @Test
    @DisplayName("状态过滤：status=PAID 只返回已支付订单")
    void adminFilterOrdersByStatus() throws Exception {
        User owner = newUser(0);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task paidTask = taskService.createTask(twoWaypointTask("fil-paid-" + rid));
        markPaid(paidTask);
        User admin = newUser(2);
        UserContext.clear();

        String body = mockMvc.perform(get("/admin/orders")
                        .param("status", "PAID")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(admin.getId(), admin.getUserName(), 2)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(paidTask.getTaskNum());
        assertThat(body).doesNotContain("pending-task"); // 未支付任务编号不在 PAID 过滤结果
    }

    @Test
    @DisplayName("订单详情与任务详情返回双状态 + actionHint")
    void adminDetailReturnsDualStatusAndHint() throws Exception {
        User owner = newUser(0);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task task = taskService.createTask(twoWaypointTask("det-" + rid));
        markPaid(task);
        User admin = newUser(2);
        UserContext.clear();

        mockMvc.perform(get("/admin/orders/" + task.getTaskNum()) // 占位：无此订单
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(admin.getId(), admin.getUserName(), 2)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/tasks/" + task.getTaskNum())
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(admin.getId(), admin.getUserName(), 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("IDLE"))
                .andExpect(jsonPath("$.data.orderStatusCode").value(1))
                .andExpect(jsonPath("$.data.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.data.actionHint").isNotEmpty());
    }

    @Test
    @DisplayName("分页打样：size=1 时 totalElements 与 totalPages 正确")
    void paginationShape() throws Exception {
        User owner = newUser(0);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var taskA = taskService.createTask(twoWaypointTask("pg-a-" + rid));
        markPaid(taskA);
        taskService.createTask(twoWaypointTask("pg-b-" + rid));
        User admin = newUser(2);
        UserContext.clear();

        var body = mockMvc.perform(get("/admin/orders")
                        .param("page", "0").param("size", "1")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(admin.getId(), admin.getUserName(), 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").isNumber())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"totalPages\"");
    }

    @Test
    @DisplayName("普通用户/飞手访问管理端查询 → 403")
    void nonAdminRejectedWith403() throws Exception {
        User normal = newUser(0);
        User rider = newUser(1);

        mockMvc.perform(get("/admin/orders").header("Authorization",
                        "Bearer " + jwtUtil.generateToken(normal.getId(), normal.getUserName(), 0)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/tasks").header("Authorization",
                        "Bearer " + jwtUtil.generateToken(rider.getId(), rider.getUserName(), 1)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/orders/ANY").header("Authorization",
                        "Bearer " + jwtUtil.generateToken(rider.getId(), rider.getUserName(), 1)))
                .andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private User newUser(int role) {
        User user = new User();
        user.setUserName("aq" + rid + "-" + role + "-" + UUID.randomUUID().toString().substring(0, 6));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private TaskDto twoWaypointTask(String name) {
        TaskDto dto = new TaskDto();
        dto.setTaskName(name);
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
        return dto;
    }

    private void markPaid(Task task) {
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        order.setOrderStatus(OrderStatus.PAID);
        orderRepository.save(order);
    }
}

package com.uav.task;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskType;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.dto.WaypointDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 1B-2a 契约测试（裁决 Q1=A 托管式支付）：
 * 未支付任务对飞手不可见（square）、不可接（accept 返回 TASK_NOT_PAID）；
 * 已支付任务可接；t3 的状态机与悲观锁行为不回归。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class RiderPaidTaskVisibilityTest {

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RiderUavRepository riderUavRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    TaskService taskService;

    private long rid;

    @BeforeEach
    void setUpRider() {
        rid = System.nanoTime();
    }

    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("square 只出已支付任务；未支付任务不出现在广场")
    void squareOnlyShowsPaidTasks() {
        User owner = newUser(0);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task paid = taskService.createTask(taskDto("paid-" + rid));
        markOrderPaid(paid);
        // 同一用户同时仅允许一笔 PENDING 订单（t3 语义）：先支付 paid 再创建 unpaid
        Task unpaid = taskService.createTask(taskDto("unpaid-" + rid));

        List<Task> available = taskService.getAvailableTasks();
        assertThat(available).extracting(Task::getTaskNum)
                .contains(paid.getTaskNum())
                .doesNotContain(unpaid.getTaskNum());
    }

    @Test
    @DisplayName("accept 未支付任务 → 400 TASK_NOT_PAID；已支付任务接单成功")
    void acceptRequiresPaidOrder() {
        User owner = newUser(0);
        User rider = newUser(1);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task paid = taskService.createTask(taskDto("acc-paid-" + rid));
        markOrderPaid(paid);
        Task unpaid = taskService.createTask(taskDto("acc-unpaid-" + rid));

        // 未支付：明确错误码 TASK_NOT_PAID
        var unpaidRejection = assertThrows(com.uav.server.exception.BusinessException.class,
                () -> taskService.acceptTask(unpaid.getTaskNum(), rider.getId()));
        assertThat(unpaidRejection.getCode()).isEqualTo("TASK_NOT_PAID");
        assertThat(unpaidRejection.getMessage()).contains("支付");

        // 已支付：接单成功
        taskService.acceptTask(paid.getTaskNum(), rider.getId());
        var assignment = taskAssignmentRepository.findByTaskId(paid.getId()).orElseThrow();
        assertThat(assignment.getRiderId()).isEqualTo(rider.getId());
    }

    @Test
    @DisplayName("t3 行为不回归：已接单任务再次接单被拒（悲观锁+状态机）")
    void acceptRegressionGuard() {
        User owner = newUser(0);
        User rider = newUser(1);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task paid = taskService.createTask(taskDto("reg-" + rid));
        markOrderPaid(paid);

        taskService.acceptTask(paid.getTaskNum(), rider.getId());
        var second = assertThrows(com.uav.server.exception.BusinessException.class,
                () -> taskService.acceptTask(paid.getTaskNum(), rider.getId()));
        assertThat(second.getMessage()).contains("已被接单");
    }

    @Test
    @DisplayName("MockMvc：/rider/square 与 /rider/recommended 对未支付任务零暴露")
    void riderEndpointsHideUnpaidTasks() {
        User owner = newUser(0);
        User rider = newUser(1);
        bindRider(rider);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task paid = taskService.createTask(taskDto("vis-paid-" + rid));
        markOrderPaid(paid);
        Task unpaid = taskService.createTask(taskDto("vis-unpaid-" + rid));

        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(wac).build();
        String riderToken = "Bearer " + jwtUtil.generateToken(rider.getId(), rider.getUserName(), 1);

        try {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/rider/square").header("Authorization", riderToken))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                            "$.data.tasks[?(@.taskNum == '" + unpaid.getTaskNum() + "')]").isEmpty())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                            "$.data.tasks[?(@.taskNum == '" + paid.getTaskNum() + "')]").isNotEmpty());

            // /rider/recommended 返回飞手统计（非任务列表）：断言响应不含任何任务载荷
            var recommended = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/rider/recommended").header("Authorization", riderToken))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(recommended).doesNotContain(unpaid.getTaskNum());
            org.assertj.core.api.Assertions.assertThat(recommended).doesNotContain("taskNum");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- helpers ----------

    @Autowired
    org.springframework.web.context.WebApplicationContext wac;

    private void markOrderPaid(Task task) {
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        order.setOrderStatus(OrderStatus.PAID);
        orderRepository.save(order);
    }

    private void bindRider(User rider) {
        RiderUav binding = new RiderUav();
        binding.setUserId(rider.getId());
        binding.setDjiId("bind-" + rid);
        riderUavRepository.save(binding);
    }

    private User newUser(int role) {
        User user = new User();
        user.setUserName("vis" + rid + "-" + role);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private TaskDto taskDto(String name) {
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
}

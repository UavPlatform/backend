package com.uav.task;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.enums.TaskType;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.user.pojo.entity.User;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.vo.TaskActionHints;
import com.uav.task.service.TaskService;
import com.uav.user.mapper.UserRepository;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.pojo.entity.RiderUav;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1B-9a 契约测试：完成说明落库可查、任务状态×订单状态×操作提示矩阵、超长说明拒绝。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class CompletionSemanticsTest {

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RiderUavRepository riderUavRepository;

    @Autowired
    TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    TaskService taskService;

    @Autowired
    org.springframework.web.context.WebApplicationContext wac;

    private long rid;

    @BeforeEach
    void setUp() {
        rid = System.nanoTime();
    }

    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("完成说明落库并经任务详情可查（含状态矩阵字段）")
    void completionNotePersistsAndQueryable() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        bindRider(rider);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var task = taskService.createTask(twoWaypointTask());
        markPaid(task);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        taskService.riderCompleteTask(task.getTaskNum(), rider.getId(), "巡检完成，正射影像已生成");

        var assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElseThrow();
        assertThat(assignment.getCompleteNote()).isEqualTo("巡检完成，正射影像已生成");

        // MockMvc /task/detail（owner）：状态矩阵字段 + 完成说明
        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(wac).build();
        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/task/detail").param("taskNum", task.getTaskNum())
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                owner.getId(), owner.getUserName(), 0)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.taskStatus").value("COMPLETED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.orderStatus").value("WAITING_CONFIRM"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.completeNote").value("巡检完成，正射影像已生成"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.actionHint").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = new ObjectMapper().readTree(response).path("data");
        assertThat(data.path("actionHint").asText()).contains("验收");
    }

    @Test
    @DisplayName("完成说明可选：不传时为 null，状态矩阵字段仍返回")
    void completionNoteOptional() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        bindRider(rider);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var task = taskService.createTask(twoWaypointTask());
        markPaid(task);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        taskService.riderCompleteTask(task.getTaskNum(), rider.getId(), null);

        var assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElseThrow();
        assertThat(assignment.getCompleteNote()).isNull();

        // 真实查询路径：completeNote 为 null、actionHint 由状态矩阵计算后返回
        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(wac).build();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/task/detail").param("taskNum", task.getTaskNum())
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                owner.getId(), owner.getUserName(), 0)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.completeNote").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.actionHint").isNotEmpty());
    }

    @Test
    @DisplayName("完成说明超长（>500 字）被拒")
    void noteTooLongRejected() {
        User owner = newUser(0);
        User rider = newUser(1);
        bindRider(rider);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var task = taskService.createTask(twoWaypointTask());
        markPaid(task);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        taskService.acceptTask(task.getTaskNum(), rider.getId());

        String longNote = "长".repeat(501);
        assertThatThrownBy(() -> taskService.riderCompleteTask(task.getTaskNum(), rider.getId(), longNote))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("状态矩阵：主要 任务状态×订单状态 组合的操作提示")
    void actionHintMatrix() {
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.PENDING)).contains("待支付");
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.PAID)).contains("等待飞手接单");
        assertThat(TaskActionHints.hint(TaskStatus.IN_PROGRESS, OrderStatus.PAID)).contains("执行中");
        assertThat(TaskActionHints.hint(TaskStatus.COMPLETED, OrderStatus.WAITING_CONFIRM)).contains("验收");
        assertThat(TaskActionHints.hint(TaskStatus.COMPLETED, OrderStatus.COMPLETED)).contains("已完成");
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.CANCELLED)).contains("取消");
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.REFUNDED)).contains("退款");
        // 兜底：不返回 null
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, null)).isNotEmpty();
    }

    // ---------- helpers ----------

    private User newUser(int role) {
        User user = new User();
        user.setUserName("cmpl" + rid + "-" + role);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private void bindRider(User rider) {
        RiderUav binding = new RiderUav();
        binding.setUserId(rider.getId());
        binding.setDjiId("cmpl-" + rid);
        riderUavRepository.save(binding);
    }

    private void markPaid(com.uav.task.pojo.entity.Task task) {
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        order.setOrderStatus(OrderStatus.PAID);
        orderRepository.save(order);
    }

    private com.uav.task.pojo.entity.Task createTask(com.uav.user.pojo.entity.User owner) {
        UserContext.setUser(owner.getId(), owner.getUserName(), owner.getRole());
        com.uav.task.pojo.dto.TaskDto dto = new com.uav.task.pojo.dto.TaskDto();
        dto.setTaskName("cmpl-" + rid);
        dto.setType(TaskType.SURVEY);
        com.uav.task.pojo.dto.WaypointDto a = new com.uav.task.pojo.dto.WaypointDto();
        a.setOrderIndex(0);
        a.setLongitude(121.0);
        a.setLatitude(31.0);
        a.setAltitude(100.0);
        com.uav.task.pojo.dto.WaypointDto b = new com.uav.task.pojo.dto.WaypointDto();
        b.setOrderIndex(1);
        b.setLongitude(121.01);
        b.setLatitude(31.0);
        b.setAltitude(100.0);
        dto.setWaypoints(java.util.List.of(a, b));
        return taskService.createTask(dto);
    }

    private com.uav.task.pojo.dto.TaskDto twoWaypointTask() {
        com.uav.task.pojo.dto.TaskDto dto = new com.uav.task.pojo.dto.TaskDto();
        dto.setTaskName("cmpl2-" + rid);
        dto.setType(TaskType.SURVEY);
        com.uav.task.pojo.dto.WaypointDto a = new com.uav.task.pojo.dto.WaypointDto();
        a.setOrderIndex(0);
        a.setLongitude(121.0);
        a.setLatitude(31.0);
        a.setAltitude(100.0);
        com.uav.task.pojo.dto.WaypointDto b = new com.uav.task.pojo.dto.WaypointDto();
        b.setOrderIndex(1);
        b.setLongitude(121.01);
        b.setLatitude(31.0);
        b.setAltitude(100.0);
        dto.setWaypoints(java.util.List.of(a, b));
        return dto;
    }
}

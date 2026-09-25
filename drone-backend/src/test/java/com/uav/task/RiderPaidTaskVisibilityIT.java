package com.uav.task;

import com.uav.server.enums.TaskType;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 托管式支付契约（裁决 Q1=A）：未支付任务对飞手不可见（square）、不可接（accept 返回 TASK_NOT_PAID）；
 * 已支付任务可接；状态机与悲观锁行为不回归。MockMvc 集成测试（R9/O4）。
 *
 * <p>R2：继承 {@link IntegrationTestBase}（MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}）；
 * R3：owner 与飞手身份均由 {@link TestAccounts} 真实注册取得，不再自签 token；
 * R5：隔离靠事务回滚，唯一名由 {@code UniqueNames} 生成而非 {@code System.nanoTime()} 兜底。
 */
class RiderPaidTaskVisibilityIT extends IntegrationTestBase {

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("square 只出已支付任务；未支付任务不出现在广场")
    void squareOnlyShowsPaidTasks() {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task paid = taskService.createTask(taskDto(UniqueNames.unique("paid")));
        fixtures.markOrderPaid(paid);
        // 同一用户同时仅允许一笔 PENDING 订单（t3 语义）：先支付 paid 再创建 unpaid
        Task unpaid = taskService.createTask(taskDto(UniqueNames.unique("unpaid")));

        List<Task> available = taskService.getAvailableTasks();
        assertThat(available).extracting(Task::getTaskNum)
                .contains(paid.getTaskNum())
                .doesNotContain(unpaid.getTaskNum());
    }

    @Test
    @DisplayName("accept 未支付任务 → 400 TASK_NOT_PAID；已支付任务接单成功")
    void acceptRequiresPaidOrder() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task paid = taskService.createTask(taskDto(UniqueNames.unique("acc-paid")));
        fixtures.markOrderPaid(paid);
        Task unpaid = taskService.createTask(taskDto(UniqueNames.unique("acc-unpaid")));

        // 未支付：明确错误码 TASK_NOT_PAID
        var unpaidRejection = assertThrows(BusinessException.class,
                () -> taskService.acceptTask(unpaid.getTaskNum(), rider.id()));
        assertThat(unpaidRejection.getCode()).isEqualTo("TASK_NOT_PAID");
        assertThat(unpaidRejection.getMessage()).contains("支付");

        // 已支付：接单成功
        taskService.acceptTask(paid.getTaskNum(), rider.id());
        var assignment = taskAssignmentRepository.findByTaskId(paid.getId()).orElseThrow();
        assertThat(assignment.getRiderId()).isEqualTo(rider.id());
    }

    @Test
    @DisplayName("t3 行为不回归：已接单任务再次接单被拒（悲观锁+状态机）")
    void acceptRegressionGuard() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task paid = taskService.createTask(taskDto(UniqueNames.unique("reg")));
        fixtures.markOrderPaid(paid);

        taskService.acceptTask(paid.getTaskNum(), rider.id());
        var second = assertThrows(BusinessException.class,
                () -> taskService.acceptTask(paid.getTaskNum(), rider.id()));
        assertThat(second.getMessage()).contains("已被接单");
    }

    @Test
    @DisplayName("MockMvc：/rider/square 与 /rider/recommended 对未支付任务零暴露")
    void riderEndpointsHideUnpaidTasks() {
        TestAccounts.Account owner = accounts().registerUser();
        // 飞手端点带 @RequireDrone：真实注册时就带 djiId 绑定，token 亦为服务端签发
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task paid = taskService.createTask(taskDto(UniqueNames.unique("vis-paid")));
        fixtures.markOrderPaid(paid);
        Task unpaid = taskService.createTask(taskDto(UniqueNames.unique("vis-unpaid")));

        String riderToken = rider.authorization();

        try {
            mockMvc.perform(get("/rider/square").header("Authorization", riderToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.data.tasks[?(@.taskNum == '" + unpaid.getTaskNum() + "')]").isEmpty())
                    .andExpect(jsonPath(
                            "$.data.tasks[?(@.taskNum == '" + paid.getTaskNum() + "')]").isNotEmpty());

            // /rider/recommended 返回飞手统计（非任务列表）：断言响应不含任何任务载荷
            var recommended = mockMvc.perform(get("/rider/recommended").header("Authorization", riderToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(recommended).doesNotContain(unpaid.getTaskNum());
            assertThat(recommended).doesNotContain("taskNum");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- helpers ----------

    private TaskDto taskDto(String name) {
        TaskDto dto = fixtures.twoWaypointTask(name);
        dto.setType(TaskType.SURVEY);
        return dto;
    }
}

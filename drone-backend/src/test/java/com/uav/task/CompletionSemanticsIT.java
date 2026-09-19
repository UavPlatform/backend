package com.uav.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.vo.TaskActionHints;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 完成说明落库可查、任务状态×订单状态×操作提示矩阵、超长说明拒绝（MockMvc 集成测试，R9/O4）。
 *
 * <p>R2：继承 {@link IntegrationTestBase}（MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}），
 * 不再逐类用 {@code MockMvcBuilders.webAppContextSetup} 搭建；R3：身份由 {@link TestAccounts}
 * 真实注册/登录取得，不再自签 token；R7：任务 DTO 与订单支付由共享工厂构造。
 *
 * <p>R5：隔离靠事务回滚；本类断言的完成说明/状态矩阵均在业务事务提交前即可见，
 * 不依赖 {@code afterCommit} 派发，故 {@code @Transactional} 不会吞掉覆盖。
 */
class CompletionSemanticsIT extends IntegrationTestBase {

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("完成说明落库并经任务详情可查（含状态矩阵字段）")
    void completionNotePersistsAndQueryable() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        var task = taskService.createTask(fixtures.twoWaypointTask());
        fixtures.markOrderPaid(task);
        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        taskService.acceptTask(task.getTaskNum(), rider.id());
        taskService.riderCompleteTask(task.getTaskNum(), rider.id(), "巡检完成，正射影像已生成");

        var assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElseThrow();
        assertThat(assignment.getCompleteNote()).isEqualTo("巡检完成，正射影像已生成");

        // MockMvc /task/detail（owner）：状态矩阵字段 + 完成说明
        var response = mockMvc.perform(get("/task/detail").param("taskNum", task.getTaskNum())
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.orderStatus").value("WAITING_CONFIRM"))
                .andExpect(jsonPath("$.data.completeNote").value("巡检完成，正射影像已生成"))
                .andExpect(jsonPath("$.data.actionHint").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = new ObjectMapper().readTree(response).path("data");
        assertThat(data.path("actionHint").asText()).contains("验收");
    }

    @Test
    @DisplayName("完成说明可选：不传时为 null，状态矩阵字段仍返回")
    void completionNoteOptional() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        var task = taskService.createTask(fixtures.twoWaypointTask());
        fixtures.markOrderPaid(task);
        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        taskService.acceptTask(task.getTaskNum(), rider.id());
        taskService.riderCompleteTask(task.getTaskNum(), rider.id(), null);

        var assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElseThrow();
        assertThat(assignment.getCompleteNote()).isNull();

        // 真实查询路径：completeNote 为 null、actionHint 由状态矩阵计算后返回
        mockMvc.perform(get("/task/detail").param("taskNum", task.getTaskNum())
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completeNote").doesNotExist())
                .andExpect(jsonPath("$.data.actionHint").isNotEmpty());
    }

    @Test
    @DisplayName("完成说明超长（>500 字）被拒")
    void noteTooLongRejected() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        var task = taskService.createTask(fixtures.twoWaypointTask());
        fixtures.markOrderPaid(task);
        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        taskService.acceptTask(task.getTaskNum(), rider.id());

        String longNote = "长".repeat(501);
        assertThatThrownBy(() -> taskService.riderCompleteTask(task.getTaskNum(), rider.id(), longNote))
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
}

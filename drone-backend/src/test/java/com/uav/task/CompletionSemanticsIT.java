package com.uav.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.server.enums.MatchStatus;
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
 * 完成说明落库可查、任务状态×订单状态×撮合状态操作提示矩阵、超长说明拒绝（MockMvc 集成测试，R9/O4）。
 *
 * <p>TASK-BACKEND-004：接单路径从「支付后大厅抢单（acceptTask）」迁移到「选定 → 支付 →
 * 飞手双确认」；交付前置履约证据（attachment），完成/验收按 ADR-0003 决定 5 走
 * PENDING_ACCEPTANCE → CLOSED。造数经 {@code fixtures.awaitingRiderConfirm} +
 * 生产 {@code riderConfirmOrder}，交付前用 {@code fixtures.evidence} 造证据。
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

    /** 造「已双确认可执飞」任务：选定+支付造数 → 生产飞手确认（落 assignment + IN_PROGRESS）。 */
    private Task confirmedTask(TestAccounts.Account owner, TestAccounts.Account rider) {
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task task = taskService.createTask(fixtures.twoWaypointTask());
        fixtures.awaitingRiderConfirm(task, rider.id());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());
        return task;
    }

    @Test
    @DisplayName("完成说明落库并经任务详情可查（含状态矩阵字段）")
    void completionNotePersistsAndQueryable() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        Task task = confirmedTask(owner, rider);
        fixtures.evidence(task.getTaskNum(), rider.id());
        taskService.riderCompleteTask(task.getTaskNum(), rider.id(), "巡检完成，正射影像已生成");

        var assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElseThrow();
        assertThat(assignment.getCompleteNote()).isEqualTo("巡检完成，正射影像已生成");

        // MockMvc /task/detail（owner）：状态矩阵字段 + 完成说明
        var response = mockMvc.perform(get("/task/detail").param("taskNum", task.getTaskNum())
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.orderStatus").value("WAITING_CONFIRM"))
                .andExpect(jsonPath("$.data.matchStatus").value("PENDING_ACCEPTANCE"))
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
        Task task = confirmedTask(owner, rider);
        fixtures.evidence(task.getTaskNum(), rider.id());
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
        Task task = confirmedTask(owner, rider);

        String longNote = "长".repeat(501);
        assertThatThrownBy(() -> taskService.riderCompleteTask(task.getTaskNum(), rider.id(), longNote))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("状态矩阵：任务状态×订单状态×撮合状态 组合的操作提示（ADR-0003 全阶段覆盖）")
    void actionHintMatrix() {
        // 撮合主路径（matchStatus 驱动）
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.MATCHING, MatchStatus.SEEKING_RIDER))
                .contains("应征");
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.MATCHING, MatchStatus.NEGOTIATING))
                .contains("洽谈");
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.PENDING, MatchStatus.AWAITING_PAYMENT))
                .contains("待支付");
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.PAID, MatchStatus.AWAITING_RIDER_CONFIRM))
                .contains("等待飞手确认");
        assertThat(TaskActionHints.hint(TaskStatus.IN_PROGRESS, OrderStatus.PAID, MatchStatus.CONFIRMED))
                .contains("执行中");
        assertThat(TaskActionHints.hint(TaskStatus.COMPLETED, OrderStatus.WAITING_CONFIRM,
                MatchStatus.PENDING_ACCEPTANCE)).contains("验收");
        assertThat(TaskActionHints.hint(TaskStatus.COMPLETED, OrderStatus.COMPLETED, MatchStatus.CLOSED))
                .contains("已完成");
        // 订单终态优先于撮合状态
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.CANCELLED, MatchStatus.NEGOTIATING))
                .contains("取消");
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.REFUNDED, MatchStatus.AWAITING_PAYMENT))
                .contains("退款");
        // 存量回退：matchStatus 未回填（null）时按旧双状态矩阵兜底，且不返回 null
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, OrderStatus.PENDING, null)).contains("待支付");
        assertThat(TaskActionHints.hint(TaskStatus.IDLE, null, null)).isNotEmpty();
    }
}

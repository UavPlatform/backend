package com.uav.task;

import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.TaskType;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.order.mapper.OrderRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务广场（/rider/square）撮合可见性（TASK-BACKEND-004 / ADR-0003 闲鱼式撮合）。
 *
 * <p>取代旧「托管式支付」契约（裁决 Q1=A：仅已支付任务可见、accept 返回 TASK_NOT_PAID）——
 * 该契约随 ADR-0003 主路径整体移除：支付发生在用户选定应征之后，广场改为浏览
 * <b>待撮合</b>任务（SEEKING_RIDER / NEGOTIATING），并回显 cargo 字段供飞手判断是否应征；
 * 旧 {@code POST /rider/accept} 直指派端点已删除（干净切换，无 shim）。
 *
 * <p>R2：继承 {@link IntegrationTestBase}（MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}）；
 * R3：身份由 {@link TestAccounts} 真实注册取得；R5：事务回滚隔离；R7：造数走共享工厂。
 */
class RiderSquareVisibilityIT extends IntegrationTestBase {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("square 只出待撮合任务（招募中/洽谈中）：已选定进入待支付的任务不再出现在广场")
    void squareOnlyShowsMatchingTasks() {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task seeking = taskService.createTask(taskDto(UniqueNames.unique("seeking"), null));
        Task negotiating = taskService.createTask(taskDto(UniqueNames.unique("negotiating"), null));
        // 第二个飞手应征使任务进入洽谈中——此处用共享工厂直造应征（应征链路本身由 TaskApplicationIT 覆盖）
        fixtures.taskApplication(negotiating, fixtures.rider().getId());
        taskRepositorySetMatch(negotiating, MatchStatus.NEGOTIATING);
        Task selected = taskService.createTask(taskDto(UniqueNames.unique("selected"), null));
        fixtures.selectAndLock(selected, fixtures.rider().getId());

        List<String> visible = taskService.getAvailableTasks().stream().map(Task::getTaskNum).toList();
        assertThat(visible)
                .contains(seeking.getTaskNum(), negotiating.getTaskNum())
                .doesNotContain(selected.getTaskNum());
    }

    @Test
    @DisplayName("square 不要求订单已支付：待撮合任务的订单为 MATCHING 草稿（未支付）也可见")
    void squareDoesNotRequirePaidOrder() {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task task = taskService.createTask(taskDto(UniqueNames.unique("unpaid"), null));

        var order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        assertThat(order.getOrderStatus().name()).isEqualTo("MATCHING");

        assertThat(taskService.getAvailableTasks()).extracting(Task::getTaskNum)
                .contains(task.getTaskNum());
    }

    @Test
    @DisplayName("square 回显 cargo 字段（cargoWeightKg/cargoCategory），供飞手判断机型")
    void squareExposesCargoFields() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task task = taskService.createTask(taskDto(UniqueNames.unique("cargo"), "200.00"));

        TestAccounts.Account rider = accounts().registerRider();
        mockMvc.perform(get("/rider/square").header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.tasks[?(@.taskNum == '" + task.getTaskNum() + "')].cargoWeightKg")
                        .value(org.hamcrest.Matchers.hasItem(200.00)))
                .andExpect(jsonPath(
                        "$.data.tasks[?(@.taskNum == '" + task.getTaskNum() + "')].cargoCategory")
                        .value(org.hamcrest.Matchers.hasItem("CONSTRUCTION")))
                .andExpect(jsonPath(
                        "$.data.tasks[?(@.taskNum == '" + task.getTaskNum() + "')].matchStatus")
                        .value(org.hamcrest.Matchers.hasItem("SEEKING_RIDER")));
    }

    @Test
    @DisplayName("旧直指派端点已移除：POST /rider/accept 不再有处理器映射（干净切换，无兼容 shim）")
    void legacyAcceptEndpointRemoved() throws Exception {
        TestAccounts.Account rider = accounts().registerRider();
        mockMvc.perform(get("/rider/square").header("Authorization", rider.authorization()))
                .andExpect(status().isOk());
        // 端点删除后 Spring 无处理器映射：请求必然失败且不返回业务成功体
        // （未映射路径经 GlobalExceptionHandler 兜底，具体状态码不作契约，只断言「不再成功」）
        var resp = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/rider/accept").param("taskNum", "ANY")
                        .header("Authorization", rider.authorization()))
                .andReturn().getResponse();
        assertThat(resp.getStatus()).as("旧 accept 端点不得再可用").isGreaterThanOrEqualTo(400);
        assertThat(resp.getContentAsString()).doesNotContain("接单成功");
    }

    @Test
    @DisplayName("MockMvc：/rider/recommended 返回飞手统计而非任务载荷（既有契约不回归）")
    void recommendedReturnsStatsOnly() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task task = taskService.createTask(taskDto(UniqueNames.unique("rec"), null));
        TestAccounts.Account rider = accounts().registerRider();

        String recommended = mockMvc.perform(get("/rider/recommended")
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(recommended).doesNotContain(task.getTaskNum());
        assertThat(recommended).doesNotContain("taskNum");
    }

    // ---------- helpers ----------

    /** 吊运任务 DTO（含货物字段时为 TRANSPORT；否则 SURVEY，仅测可见性）。 */
    private TaskDto taskDto(String name, String cargoWeightKg) {
        TaskDto dto = fixtures.twoWaypointTask(name);
        if (cargoWeightKg != null) {
            dto.setType(TaskType.TRANSPORT);
            dto.setCargoWeightKg(new BigDecimal(cargoWeightKg));
            dto.setCargoCategory(com.uav.server.enums.CargoCategory.CONSTRUCTION);
        }
        return dto;
    }

    /** 直改撮合状态（仅造数：应征进入洽谈中的状态推进本由 apply 完成，此处绕开计价链路）。 */
    private void taskRepositorySetMatch(Task task, MatchStatus matchStatus) {
        var reloaded = taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
        reloaded.setMatchStatus(matchStatus);
        taskRepository.save(reloaded);
    }
}

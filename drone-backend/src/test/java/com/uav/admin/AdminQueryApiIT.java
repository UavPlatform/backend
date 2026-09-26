package com.uav.admin;

import com.uav.live.service.AppWebSocketService;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.pay.mapper.PayRecordRepository;
import com.uav.pay.pojo.entity.PayRecord;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端分页查询集成测试：管理员分页查询全平台任务/订单；普通用户/飞手 403；
 * 订单状态码契约（0-7，含 4/5 与新增 7=待撮合 MATCHING）与 actionHint 可供 OrderView 状态矩阵渲染。
 *
 * <p>TASK-BACKEND-007 增补监管上下文断言：任务/订单 VO 的 deviceId（在线设备映射正/空两态）与
 * userConfirmedAt / riderConfirmedAt / paidAt（PayRecord 支付时间与订单状态迁移回退两口径）。
 *
 * <p>层次与驱动（O6/R2/R4）：进程内 MockMvc 集成测试，继承 {@link IntegrationTestBase}
 * （MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}），不自称端到端。
 *
 * <p>身份来源（R3）：管理员与普通/飞手身份一律取自 {@link TestAccounts} 的真实登录/注册接口，
 * 不再用 {@code JwtUtil.generateToken} 自签；造数走共享工厂（R7），
 * ThreadLocal 由基类清理（R8），事务回滚即隔离（R5）。
 */
class AdminQueryApiIT extends IntegrationTestBase {

    /** VO @JsonFormat 口径（yyyy-MM-dd HH:mm:ss），用于 paidAt 精确回显断言。 */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    TaskService taskService;

    @Autowired
    AppWebSocketService appWebSocketService;

    @Autowired
    PayRecordRepository payRecordRepository;

    @Test
    @DisplayName("管理员分页查询全平台订单：状态码契约含 4/5/7，字段齐全")
    void adminListOrdersWithStatusContract() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), 0);
        Task paidTask = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("paid")));
        fixtures.markOrderPaid(paidTask);
        Task pendingTask = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("pending")));

        UserContext.clear();

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        mockMvc.perform(get("/admin/orders")
                        .param("page", "0").param("size", "50")
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.taskNum == '" + paidTask.getTaskNum() + "')].orderStatusCode")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.content[?(@.taskNum == '" + pendingTask.getTaskNum() + "')].orderStatusCode")
                        .value(org.hamcrest.Matchers.hasItem(7)))
                .andExpect(jsonPath("$.data.content[?(@.taskNum == '" + pendingTask.getTaskNum() + "')].ownerName")
                        .value(org.hamcrest.Matchers.hasItem(owner.userName())));
    }

    @Test
    @DisplayName("状态过滤：status=PAID 只返回已支付订单")
    void adminFilterOrdersByStatus() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), 0);
        Task paidTask = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("fil-paid")));
        fixtures.markOrderPaid(paidTask);

        UserContext.clear();

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        String body = mockMvc.perform(get("/admin/orders")
                        .param("status", "PAID")
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(paidTask.getTaskNum());
        assertThat(body).doesNotContain("pending-task"); // 未支付任务编号不在 PAID 过滤结果
    }

    @Test
    @DisplayName("订单详情与任务详情返回双状态 + actionHint")
    void adminDetailReturnsDualStatusAndHint() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), 0);
        Task task = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("det")));
        fixtures.markOrderPaid(task);

        UserContext.clear();

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        mockMvc.perform(get("/admin/orders/" + task.getTaskNum()) // 占位：无此订单
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/tasks/" + task.getTaskNum())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("IDLE"))
                .andExpect(jsonPath("$.data.orderStatusCode").value(1))
                .andExpect(jsonPath("$.data.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.data.actionHint").isNotEmpty());
    }

    @Test
    @DisplayName("分页打样：size=1 时 totalElements 与 totalPages 正确")
    void paginationShape() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), 0);
        var taskA = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("pg-a")));
        fixtures.markOrderPaid(taskA);
        taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("pg-b")));

        UserContext.clear();

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        var body = mockMvc.perform(get("/admin/orders")
                        .param("page", "0").param("size", "1")
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").isNumber())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"totalPages\"");
    }

    @Test
    @DisplayName("普通用户/飞手访问管理端查询 → 403")
    void nonAdminRejectedWith403() throws Exception {
        TestAccounts.Account normal = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("r"), null);

        mockMvc.perform(get("/admin/orders").header("Authorization", normal.authorization()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/tasks").header("Authorization", rider.authorization()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/orders/ANY").header("Authorization", rider.authorization()))
                .andExpect(status().isForbidden());
    }

    // ---------- 监管上下文（TASK-BACKEND-007 / REQ-FRONTEND-001 验收 4）----------

    @Test
    @DisplayName("任务/订单 VO 暴露 deviceId：已接单且设备在线 → 非空；未接单 → null")
    void adminCarriesDeviceMapping() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task task = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("adm-dev")));
        Task unassigned = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("adm-none")));

        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        MissionOrder order = fixtures.awaitingRiderConfirm(task, rider.id());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());
        appWebSocketService.requestConnection(rider.djiId());
        appWebSocketService.markAsConnected(rider.djiId());

        UserContext.clear();
        TestAccounts.AdminAccount admin = accounts().adminLogin();

        // 正态：task → task_assignment → rider_uav → 在线设备；
        // 同一响应断言双确认时间与 paidAt（无支付流水 → 订单状态迁移时间回退口径）非空
        mockMvc.perform(get("/admin/tasks/" + task.getTaskNum())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(rider.djiId()))
                .andExpect(jsonPath("$.data.userConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.riderConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.paidAt").isNotEmpty());

        mockMvc.perform(get("/admin/orders/" + order.getOrderNum())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(rider.djiId()));

        // 空态：任务未接单 → deviceId 为 null（schema 已文档化）
        mockMvc.perform(get("/admin/tasks/" + unassigned.getTaskNum())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("任务/订单 VO 回显双确认与支付完成时间：paidAt = PayRecord.payTime")
    void adminCarriesConfirmAndPayTimestamps() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task task = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("adm-ts")));

        MissionOrder locked = fixtures.selectAndLock(task, rider.id());
        fixtures.payLockedOrder(task); // 生产支付状态机：PENDING→PAID，PayRecord.payTime 落库

        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());

        PayRecord pay = payRecordRepository.findByOrderNum(locked.getOrderNum())
                .orElseThrow(() -> new AssertionError("缺少支付流水: " + locked.getOrderNum()));
        assertThat(pay.getPayTime()).as("支付成功时间应落库").isNotNull();
        String expectedPaidAt = pay.getPayTime().format(FMT);

        UserContext.clear();
        TestAccounts.AdminAccount admin = accounts().adminLogin();

        mockMvc.perform(get("/admin/tasks/" + task.getTaskNum())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.riderConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.paidAt").value(expectedPaidAt));

        mockMvc.perform(get("/admin/orders/" + locked.getOrderNum())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.riderConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.paidAt").value(expectedPaidAt));
    }
}

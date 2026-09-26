package com.uav.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.pay.service.WeChatPayService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.CargoCategory;
import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.enums.TaskType;
import com.uav.server.exception.BusinessException;
import com.uav.server.exception.PayNotifyException;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import com.uav.task.service.TaskApplicationService;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 撮合状态机全链路（TASK-BACKEND-004 / REQ-BACKEND-001 验收 1-5 / ADR-0003）。
 *
 * <p>覆盖：发布（草稿订单不强制支付、可多需求）→ 多飞手应征（机型不同报价不同）→
 * 用户选定+约定时间（锁定 totalAmount=quotedAmount、其余应征关闭）→ 支付（改价攻击被拒）→
 * 双确认门禁（未确认不可 IN_PROGRESS、非选定飞手被拒）→ 履约证据门禁（无证据不能交付/验收）
 * → 用户验收结案（CLOSED/COMPLETED）→ 非法状态迁移明确错误码。
 *
 * <p>层次与驱动（R2/R4/R9）：继承 {@link IntegrationTestBase}（MOCK + MockMvc +
 * {@code @Transactional} 回滚隔离）；身份由 {@link TestAccounts} 真实注册取得（R3）；
 * 造数走共享工厂 {@code fixtures}（R7）；ThreadLocal 由基类清理（R8）。
 *
 * <p>支付步骤用共享工厂 {@code fixtures.payLockedOrder}（生产 {@code handleNotify} 状态机，
 * 含撮合状态推进）；{@code /pay/{orderNum}} 端点本身由 {@code MockPayFlowIT} 覆盖——
 * 本类未开 mock 支付开关，直接调端点会停在 openid 门禁。
 */
class MatchFlowIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskApplicationRepository taskApplicationRepository;

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Autowired
    private com.uav.pay.mapper.PayRecordRepository payRecordRepository;

    @Autowired
    private WeChatPayService weChatPayService;

    // ---------- 造数与链路辅助 ----------

    /** 吊运任务 DTO（2 航点 + 货物字段，满足应征计价前提）。 */
    private TaskDto transportTask(String name) {
        TaskDto dto = fixtures.twoWaypointTask(name);
        dto.setType(TaskType.TRANSPORT);
        dto.setCargoWeightKg(new BigDecimal("2.00")); // ≤ M350RTK 最大载重 2.7kg，FC30/M350RTK 均可应征
        dto.setCargoCategory(CargoCategory.CONSTRUCTION);
        return dto;
    }

    /** 注册飞手并生产绑机映射指定机型（第二个绑定，注册自带的未映射绑定不影响门禁）。 */
    private TestAccounts.Account riderWithModel(String modelCode) throws Exception {
        TestAccounts.Account rider = accounts().registerRider();
        Long modelId = aircraftModelRepository.findByModelCode(modelCode)
                .orElseThrow(() -> new AssertionError("缺少机型种子: " + modelCode))
                .getId();
        fixtures.bindDrone(rider.id(), UniqueNames.djiId(), modelId);
        return rider;
    }

    private long modelId(String modelCode) {
        return aircraftModelRepository.findByModelCode(modelCode)
                .orElseThrow(() -> new AssertionError("缺少机型种子: " + modelCode))
                .getId();
    }

    /** 属主发布任务（服务端链路），返回任务。 */
    private Task publish(TestAccounts.Account owner, String name) {
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        return taskService.createTask(transportTask(name));
    }

    /** HTTP 应征，返回应征响应 JSON（失败时携带响应体，便于定位错误码）。 */
    private JsonNode apply(TestAccounts.Account rider, String taskNum, String modelCode) throws Exception {
        var result = mockMvc.perform(post("/rider/apply")
                        .header("Authorization", rider.authorization())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"taskNum\":\"" + taskNum + "\",\"aircraftModelId\":"
                                + modelId(modelCode) + "}"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("apply 应成功，响应=%s", body).isEqualTo(200);
        assertThat(MAPPER.readTree(body).path("success").asBoolean())
                .as("apply success=false，响应=%s", body).isTrue();
        return MAPPER.readTree(body);
    }

    /** HTTP 用户选定应征并下单（200），返回任务详情响应。 */
    private JsonNode selectOk(TestAccounts.Account owner, String taskNum, long applicationId)
            throws Exception {
        return MAPPER.readTree(mockMvc.perform(post("/task/" + taskNum + "/select-rider")
                        .header("Authorization", owner.authorization())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":" + applicationId
                                + ",\"scheduledTime\":\"2030-10-01 10:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString());
    }

    /** 走生产链路推进到「已支付、待飞手确认」（选定 + 生产支付状态机）。 */
    private Task paidTask(TestAccounts.Account owner, TestAccounts.Account rider, String name) {
        Task task = publish(owner, name);
        taskApplicationService.apply(task.getTaskNum(), rider.id(), modelId("FC30"), null);
        TaskApplication application = taskApplicationRepository
                .findByTaskIdAndRiderId(task.getId(), rider.id()).orElseThrow();
        taskService.selectRider(task.getTaskNum(), owner.id(), application.getId(),
                LocalDateTime.now().plusDays(1));
        fixtures.payLockedOrder(task);
        return taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("发布：创建待撮合草稿订单（不强制支付），同一用户可并行发布多个需求（冲突点 1）")
    void publishCreatesDraftOrdersWithoutPayment() {
        TestAccounts.Account owner = accounts().registerUser();
        Task first = publish(owner, UniqueNames.unique("pub"));
        Task second = publish(owner, UniqueNames.unique("pub"));
        Task third = publish(owner, UniqueNames.unique("pub"));

        for (Task task : new Task[]{first, second, third}) {
            assertThat(task.getMatchStatus()).isEqualTo(MatchStatus.SEEKING_RIDER);
            MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.MATCHING);
            assertThat(order.getTotalAmount().signum()).isZero(); // 金额未锁定
        }
    }

    @Test
    @DisplayName("应征：不同机型系统报价不同且可复现（验收 1），列表含飞手/机型/报价（验收 3）")
    void applicationsPricedByAircraftModel() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = publish(owner, UniqueNames.unique("quote"));
        TestAccounts.Account fc30Rider = riderWithModel("FC30");
        TestAccounts.Account m350Rider = riderWithModel("M350RTK");

        JsonNode appliedFc30 = apply(fc30Rider, task.getTaskNum(), "FC30");
        JsonNode appliedM350 = apply(m350Rider, task.getTaskNum(), "M350RTK");
        BigDecimal quoteFc30 = new BigDecimal(appliedFc30.path("data").path("quotedAmount").asText());
        BigDecimal quoteM350 = new BigDecimal(appliedM350.path("data").path("quotedAmount").asText());
        assertThat(quoteFc30).isPositive();
        assertThat(quoteM350).as("机型系数 1.000 vs 1.200 → 报价必须不同").isNotEqualTo(quoteFc30);

        // 可复现：重复应征得到同一报价
        JsonNode reapply = apply(fc30Rider, task.getTaskNum(), "FC30");
        assertThat(new BigDecimal(reapply.path("data").path("quotedAmount").asText()))
                .isEqualByComparingTo(quoteFc30);

        // 应征列表（属主）：2 条，含飞手/机型/载重/报价/状态
        var list = mockMvc.perform(get("/task/" + task.getTaskNum() + "/applications")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode apps = MAPPER.readTree(list).path("data");
        for (JsonNode item : apps) {
            assertThat(item.path("riderName").asText()).isNotBlank();
            assertThat(item.path("modelCode").asText()).isIn("FC30", "M350RTK");
            assertThat(item.path("maxPayloadKg").decimalValue()).isPositive();
            assertThat(item.path("quotedAmount").decimalValue()).isPositive();
            assertThat(item.path("status").asText()).isEqualTo("ACTIVE");
        }

        // 首条应征推进撮合状态：招募中 → 洽谈中
        assertThat(taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow().getMatchStatus())
                .isEqualTo(MatchStatus.NEGOTIATING);
    }

    @Test
    @DisplayName("选定下单：锁定 totalAmount=quotedAmount、其余应征关闭、VO 回显报价/机型/时间（验收 2 前半）")
    void selectRiderLocksQuoteAndClosesOthers() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = publish(owner, UniqueNames.unique("sel"));
        TestAccounts.Account riderA = riderWithModel("FC30");
        TestAccounts.Account riderB = riderWithModel("M350RTK");
        long applicationIdA = apply(riderA, task.getTaskNum(), "FC30")
                .path("data").path("applicationId").asLong();
        long applicationIdB = apply(riderB, task.getTaskNum(), "M350RTK")
                .path("data").path("applicationId").asLong();

        // 非属主不能选定（FORBIDDEN）
        mockMvc.perform(post("/task/" + task.getTaskNum() + "/select-rider")
                        .header("Authorization", riderA.authorization())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":" + applicationIdA
                                + ",\"scheduledTime\":\"2030-10-01 10:00:00\"}"))
                .andExpect(status().isForbidden());

        // 缺约定时间被拒
        mockMvc.perform(post("/task/" + task.getTaskNum() + "/select-rider")
                        .header("Authorization", owner.authorization())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":" + applicationIdA + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.INVALID_PARAM.getCode()));

        JsonNode selected = selectOk(owner, task.getTaskNum(), applicationIdA);
        assertThat(selected.path("data").path("matchStatus").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(selected.path("data").path("cargoWeightKg").decimalValue())
                .isEqualByComparingTo("2.00");
        assertThat(selected.path("data").path("cargoCategory").asText()).isEqualTo("CONSTRUCTION");
        assertThat(selected.path("data").path("aircraftModelName").asText()).isNotBlank();
        assertThat(selected.path("data").path("quotedAmount").decimalValue()).isPositive();
        assertThat(selected.path("data").path("scheduledTime").asText()).isNotBlank();
        assertThat(selected.path("data").path("userConfirmedAt").asText()).isNotBlank();

        // 订单：PENDING + 金额严格等于报价 + 锁定应征
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        BigDecimal quoteA = new BigDecimal(String.valueOf(order.getTotalAmount()));
        TaskApplication applicationA = taskApplicationRepository.findById(applicationIdA).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(quoteA).isEqualByComparingTo(applicationA.getQuotedAmount());
        assertThat(order.getSelectedApplicationId()).isEqualTo(applicationIdA);
        assertThat(order.getScheduledTime()).isNotNull();
        assertThat(order.getUserConfirmedAt()).isNotNull();
        assertThat(order.getRiderConfirmedAt()).isNull();

        // 其余应征自动关闭（ADR-0003 决定 1）
        assertThat(taskApplicationRepository.findById(applicationIdA).orElseThrow().getStatus())
                .isEqualTo(com.uav.server.enums.ApplicationStatus.SELECTED);
        assertThat(taskApplicationRepository.findById(applicationIdB).orElseThrow().getStatus())
                .isEqualTo(com.uav.server.enums.ApplicationStatus.CLOSED);

        // task.reward 回写为成交价（= 报价）
        Task reloaded = taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
        assertThat(reloaded.getReward()).isCloseTo(applicationA.getQuotedAmount().doubleValue(),
                org.assertj.core.api.Assertions.within(1e-9));

        // 已关闭的应征不能再被选定
        mockMvc.perform(post("/task/" + task.getTaskNum() + "/select-rider")
                        .header("Authorization", owner.authorization())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":" + applicationIdB
                                + ",\"scheduledTime\":\"2030-10-01 10:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.MATCH_STATUS_INVALID.getCode()));
    }

    @Test
    @DisplayName("改价攻击：篡改 totalAmount 后支付被拒（/pay 400 AMOUNT_MISMATCH；回调同样拒绝入账）")
    void tamperedAmountCannotBePaid() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = publish(owner, UniqueNames.unique("tamper"));
        TestAccounts.Account rider = riderWithModel("FC30");
        long applicationId = apply(rider, task.getTaskNum(), "FC30")
                .path("data").path("applicationId").asLong();
        selectOk(owner, task.getTaskNum(), applicationId);

        // 直改订单金额（模拟篡改），偏离 quotedAmount 1 分
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        order.setTotalAmount(order.getTotalAmount().add(new BigDecimal("0.01")));
        orderRepository.save(order);

        mockMvc.perform(post("/pay/" + order.getOrderNum())
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.AMOUNT_MISMATCH.getCode()));
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus())
                .as("被拒后订单保持待支付")
                .isEqualTo(OrderStatus.PENDING);

        // 回调链路：金额分按篡改后的金额传入（能过 P0-3 金额比对），仍撞报价硬校验拒绝入账
        int tamperedCents = order.getTotalAmount()
                .multiply(BigDecimal.valueOf(100)).intValueExact();
        var payRecord = new com.uav.pay.pojo.entity.PayRecord();
        payRecord.setOrderNum(order.getOrderNum());
        payRecord.setUserId(owner.id());
        payRecord.setAmount(order.getTotalAmount());
        payRecord.setPayChannel("WECHAT");
        payRecord.setStatus(OrderStatus.PENDING);
        // PayRecord 需要先落库（回调按 orderNum 查流水）
        payRecordRepository.save(payRecord);

        assertThatThrownBy(() -> weChatPayService.handleNotify(
                UniqueNames.unique("tx"), order.getOrderNum(), "SUCCESS", tamperedCents))
                .isInstanceOf(PayNotifyException.class)
                .hasMessageContaining("报价");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus())
                .as("篡改金额的回调不得入账")
                .isNotEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("双确认门禁：未支付/非选定飞手不可确认，未 CONFIRMED 不可 IN_PROGRESS（验收 4）")
    void doubleConfirmGateGuardsInProgress() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = publish(owner, UniqueNames.unique("gate"));
        TestAccounts.Account riderA = riderWithModel("FC30");
        TestAccounts.Account riderB = riderWithModel("M350RTK");
        long applicationId = apply(riderA, task.getTaskNum(), "FC30")
                .path("data").path("applicationId").asLong();
        apply(riderB, task.getTaskNum(), "M350RTK");
        selectOk(owner, task.getTaskNum(), applicationId);

        // 未支付：飞手确认被拒，任务保持 IDLE
        assertThatThrownBy(() -> taskService.riderConfirmOrder(task.getTaskNum(), riderA.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("撮合状态");
        assertThat(taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow().getTaskStatus())
                .isEqualTo(TaskStatus.IDLE);

        // 支付后、飞手确认前：startExecution 门禁拒绝（DOUBLE_CONFIRM_REQUIRED）
        fixtures.payLockedOrder(task);
        Task paid = taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
        assertThat(paid.getMatchStatus()).isEqualTo(MatchStatus.AWAITING_RIDER_CONFIRM);
        assertThatThrownBy(() -> taskService.startExecution(task.getTaskNum()))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode()
                        .equals(ApiErrorCode.DOUBLE_CONFIRM_REQUIRED.getCode()));
        assertThat(taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow().getTaskStatus())
                .isEqualTo(TaskStatus.IDLE);

        // 非选定飞手确认被拒（NO_PERMISSION）
        assertThatThrownBy(() -> taskService.riderConfirmOrder(task.getTaskNum(), riderB.id()))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode().equals(ApiErrorCode.NO_PERMISSION.getCode()));

        // 选定飞手确认 → 双确认齐备 → IN_PROGRESS + CONFIRMED + 接单记录
        taskService.riderConfirmOrder(task.getTaskNum(), riderA.id());
        Task confirmed = taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
        assertThat(confirmed.getTaskStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(confirmed.getMatchStatus()).isEqualTo(MatchStatus.CONFIRMED);
        MissionOrder order = orderRepository.findByTaskId(confirmed.getId()).orElseThrow();
        assertThat(order.getRiderConfirmedAt()).isNotNull();
        assertThat(order.getUserConfirmedAt()).isNotNull();
        assertThat(taskAssignmentRepository.findByTaskId(confirmed.getId()).orElseThrow().getRiderId())
                .isEqualTo(riderA.id());

        // 幂等：重复 startExecution 不报错
        assertThat(taskService.startExecution(task.getTaskNum()).getTaskStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("履约证据门禁：无证据不能交付也不能验收；验收后订单/撮合双终态（验收 5）")
    void evidenceGateAndClosure() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = riderWithModelUnchecked("FC30");
        Task task = paidTask(owner, rider, UniqueNames.unique("ev"));

        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());

        // 无证据交付被拒
        assertThatThrownBy(() -> taskService.riderCompleteTask(task.getTaskNum(), rider.id(), null))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode()
                        .equals(ApiErrorCode.DELIVERY_EVIDENCE_REQUIRED.getCode()));
        assertThat(taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow().getMatchStatus())
                .isEqualTo(MatchStatus.CONFIRMED);

        // 无证据的验收也必须被拒（直接造 PENDING_ACCEPTANCE + WAITING_CONFIRM 的无证据态）
        Task staleTask = publish(owner, UniqueNames.unique("stale"));
        MissionOrder staleOrder = orderRepository.findByTaskId(staleTask.getId()).orElseThrow();
        staleTask.setTaskStatus(TaskStatus.COMPLETED);
        staleTask.setMatchStatus(MatchStatus.PENDING_ACCEPTANCE);
        taskRepository.save(staleTask);
        staleOrder.setOrderStatus(OrderStatus.WAITING_CONFIRM);
        orderRepository.save(staleOrder);
        assertThatThrownBy(() -> taskService.userConfirmTask(staleTask.getTaskNum(), owner.id()))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode()
                        .equals(ApiErrorCode.DELIVERY_EVIDENCE_REQUIRED.getCode()));

        // 上传证据 → 交付 → 待验收 → 用户确认 → 结案
        fixtures.evidence(task.getTaskNum(), rider.id());
        taskService.riderCompleteTask(task.getTaskNum(), rider.id(), "吊运完成");
        Task delivered = taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
        assertThat(delivered.getTaskStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(delivered.getMatchStatus()).isEqualTo(MatchStatus.PENDING_ACCEPTANCE);
        assertThat(orderRepository.findByTaskId(delivered.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.WAITING_CONFIRM);

        taskService.userConfirmTask(task.getTaskNum(), owner.id());
        Task closed = taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
        assertThat(closed.getMatchStatus()).isEqualTo(MatchStatus.CLOSED);
        assertThat(orderRepository.findByTaskId(closed.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("非法迁移：选定后不可再应征、已确认不可重新下单、结案后为终态（明确错误码）")
    void illegalTransitionsRejected() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = publish(owner, UniqueNames.unique("illegal"));
        TestAccounts.Account riderA = riderWithModelUnchecked("FC30");
        TestAccounts.Account riderB = riderWithModelUnchecked("M350RTK");
        long applicationId = apply(riderA, task.getTaskNum(), "FC30")
                .path("data").path("applicationId").asLong();
        selectOk(owner, task.getTaskNum(), applicationId);

        // 已进入待支付：新应征被拒（MATCH_STATUS_INVALID）
        assertThatThrownBy(() -> taskApplicationService.apply(
                task.getTaskNum(), riderB.id(), modelId("M350RTK"), null))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode()
                        .equals(ApiErrorCode.MATCH_STATUS_INVALID.getCode()));

        // 不能应征自己的任务
        assertThatThrownBy(() -> taskApplicationService.apply(
                task.getTaskNum(), owner.id(), modelId("FC30"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自己");

        // 支付 + 确认后（CONFIRMED）：重新选定下单被拒（状态机出边不存在）
        fixtures.payLockedOrder(task);
        taskService.riderConfirmOrder(task.getTaskNum(), riderA.id());
        assertThatThrownBy(() -> taskService.selectRider(task.getTaskNum(), owner.id(),
                applicationId, LocalDateTime.now().plusDays(2)))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode()
                        .equals(ApiErrorCode.MATCH_STATUS_INVALID.getCode()));

        // 飞手取消接单：CONFIRMED → NEGOTIATING，重新开放撮合（订单保持已支付）
        taskService.riderCancelTask(task.getTaskNum(), riderA.id());
        Task reopened = taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
        assertThat(reopened.getTaskStatus()).isEqualTo(TaskStatus.IDLE);
        assertThat(reopened.getMatchStatus()).isEqualTo(MatchStatus.NEGOTIATING);
        assertThat(orderRepository.findByTaskId(reopened.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.PAID);

        // 重新开放后可再次选定（订单已支付 → 直接回 AWAITING_RIDER_CONFIRM），飞手确认后重新执飞
        selectOk(owner, task.getTaskNum(), applicationId);
        Task reselected = taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow();
        assertThat(reselected.getMatchStatus()).isEqualTo(MatchStatus.AWAITING_RIDER_CONFIRM);
        taskService.riderConfirmOrder(task.getTaskNum(), riderA.id());
        assertThat(taskRepository.findByTaskNum(task.getTaskNum()).orElseThrow().getTaskStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
    }

    /** 注册飞手并绑机（同 riderWithModel，避免受检异常传播的场景使用）。 */
    private TestAccounts.Account riderWithModelUnchecked(String modelCode) {
        try {
            return riderWithModel(modelCode);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

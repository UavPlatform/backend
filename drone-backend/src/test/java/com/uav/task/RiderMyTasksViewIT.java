package com.uav.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.CargoCategory;
import com.uav.server.enums.TaskType;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import com.uav.task.service.TaskApplicationService;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 飞手端「我的接单」列表（/rider/my-tasks、/rider/my-tasks/history）与详情同一装配：
 * 必须带订单号、成交价、订单状态、约定时间与成交机型，飞手端卡片才能显示成交价而不是「面议」；
 * 列表按接单时间倒序（最新在前，同秒按 id 倒序）。
 *
 * <p>回归说明：{@link #activeListCoversPreConfirmStages} 在修复前失败——接单记录在飞手确认时才创建，
 * 旧实现只按接单记录查询，用户付款后「待我确认」的单不在列表里（expected 2 but was 0/1），
 * 飞手在 App 里找不到「确认订单」入口；应征中卡片的 quotedAmount 在修复前为 null（App 显示「面议」）。
 */
class RiderMyTasksViewIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private TaskService taskService;
    @Autowired private TaskApplicationService taskApplicationService;
    @Autowired private TaskApplicationRepository taskApplicationRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private AircraftModelRepository aircraftModelRepository;
    @Autowired private TaskAssignmentRepository taskAssignmentRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @PersistenceContext private EntityManager entityManager;

    private long fc30() {
        return aircraftModelRepository.findByModelCode("FC30").orElseThrow().getId();
    }

    private Task paidTask(TestAccounts.Account owner, TestAccounts.Account rider) {
        Task task = publish(owner);
        taskApplicationService.apply(task.getTaskNum(), rider.id(), fc30());
        TaskApplication application = taskApplicationRepository
                .findByTaskIdAndRiderId(task.getId(), rider.id()).orElseThrow();
        taskService.selectRider(task.getTaskNum(), owner.id(), application.getId(),
                LocalDateTime.now().plusDays(1));
        fixtures.payLockedOrder(task);
        return task;
    }

    private JsonNode tasksOf(String path, TestAccounts.Account rider) throws Exception {
        String body = mockMvc.perform(get(path).header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).path("data").path("tasks");
    }

    private Task publish(TestAccounts.Account owner) {
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        TaskDto dto = fixtures.twoWaypointTask(UniqueNames.unique("mine"));
        dto.setType(TaskType.TRANSPORT);
        dto.setCargoWeightKg(new BigDecimal("2.00"));
        dto.setCargoCategory(CargoCategory.CONSTRUCTION);
        return taskService.createTask(dto);
    }

    private static java.util.List<String> taskNums(JsonNode tasks) {
        java.util.List<String> nums = new java.util.ArrayList<>();
        tasks.forEach(t -> nums.add(t.path("taskNum").asText()));
        return nums;
    }

    @Test
    @DisplayName("进行中：含应征中、待我确认（确认前尚无接单记录）与已确认；落选与他人任务不出现")
    void activeListCoversPreConfirmStages() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        TestAccounts.Account other = accounts().registerRider();
        fixtures.bindDrone(rider.id(), UniqueNames.djiId(), fc30());
        fixtures.bindDrone(other.id(), UniqueNames.djiId(), fc30());

        Task negotiating = publish(owner);
        taskApplicationService.apply(negotiating.getTaskNum(), rider.id(), fc30());

        TestAccounts.Account owner2 = accounts().registerUser(); // 同一用户同时只允许一笔待支付
        Task lost = publish(owner2);
        taskApplicationService.apply(lost.getTaskNum(), rider.id(), fc30());
        taskApplicationService.apply(lost.getTaskNum(), other.id(), fc30());
        long otherApp = taskApplicationRepository.findByTaskIdAndRiderId(lost.getId(), other.id())
                .orElseThrow().getId();
        taskService.selectRider(lost.getTaskNum(), owner2.id(), otherApp, LocalDateTime.now().plusDays(1));

        Task awaitingMe = paidTask(owner, rider);

        JsonNode active = tasksOf("/rider/my-tasks", rider);
        assertThat(taskNums(active))
                .containsExactlyInAnyOrder(negotiating.getTaskNum(), awaitingMe.getTaskNum());
        JsonNode awaiting = java.util.stream.StreamSupport.stream(active.spliterator(), false)
                .filter(t -> t.path("taskNum").asText().equals(awaitingMe.getTaskNum()))
                .findFirst().orElseThrow();
        assertThat(awaiting.path("matchStatus").asText()).isEqualTo("AWAITING_RIDER_CONFIRM");
        assertThat(awaiting.path("totalAmount").decimalValue()).isPositive();
        assertThat(awaiting.path("scheduledTime").asText()).isNotBlank();
        assertThat(tasksOf("/rider/my-tasks", other).size()).as("他人：仅自己被选定的那单").isEqualTo(1);

        // 应征中：尚未选定、尚无成交价 → 以本人应征的平台系统报价展示（ADR-0003），不再是「面议」
        JsonNode applying = java.util.stream.StreamSupport.stream(active.spliterator(), false)
                .filter(t -> t.path("taskNum").asText().equals(negotiating.getTaskNum()))
                .findFirst().orElseThrow();
        TaskApplication myApplication = taskApplicationRepository
                .findByTaskIdAndRiderId(negotiating.getId(), rider.id()).orElseThrow();
        assertThat(applying.path("quotedAmount").isNumber()).as("应征中卡片应带本人系统报价").isTrue();
        assertThat(applying.path("quotedAmount").decimalValue())
                .isPositive()
                .isEqualByComparingTo(myApplication.getQuotedAmount());
        assertThat(applying.path("aircraftModelName").asText()).isNotBlank();

        // 确认后转为接单记录：仍在进行中且不重复，同时进入接单历史
        taskService.riderConfirmOrder(awaitingMe.getTaskNum(), rider.id());
        JsonNode afterConfirm = tasksOf("/rider/my-tasks", rider);
        assertThat(taskNums(afterConfirm))
                .containsExactlyInAnyOrder(negotiating.getTaskNum(), awaitingMe.getTaskNum());
        assertThat(taskNums(tasksOf("/rider/my-tasks/history", rider)))
                .containsExactly(awaitingMe.getTaskNum());
    }

    @Test
    @DisplayName("列表与详情同一装配：带订单号/成交价/订单状态/约定时间/机型，接单历史按接单时间倒序")
    void riderTaskListsCarryOrderFields() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        fixtures.bindDrone(rider.id(), UniqueNames.djiId(), fc30());

        Task first = paidTask(owner, rider);
        taskService.riderConfirmOrder(first.getTaskNum(), rider.id());
        Thread.sleep(20);
        Task second = paidTask(owner, rider);
        taskService.riderConfirmOrder(second.getTaskNum(), rider.id());

        for (String path : new String[]{"/rider/my-tasks", "/rider/my-tasks/history"}) {
            JsonNode tasks = tasksOf(path, rider);
            assertThat(tasks.size()).as("%s 应含两单", path).isEqualTo(2);
            for (JsonNode item : tasks) {
                MissionOrder order = orderRepository
                        .findByTaskId(item.path("id").asLong()).orElseThrow();
                assertThat(item.path("orderNum").asText()).as(path).isEqualTo(order.getOrderNum());
                assertThat(item.path("totalAmount").decimalValue()).as(path)
                        .isPositive()
                        .isEqualByComparingTo(order.getTotalAmount());
                assertThat(item.path("quotedAmount").decimalValue()).isPositive();
                assertThat(item.path("orderStatus").asText()).isEqualTo(order.getOrderStatus().name());
                assertThat(item.path("scheduledTime").asText()).isNotBlank();
                assertThat(item.path("aircraftModelName").asText()).isNotBlank();
                assertThat(item.path("riderName").asText()).isEqualTo(rider.userName());
            }
            assertThat(taskNums(tasks)).as("%s 最新接单在前", path)
                    .containsExactly(second.getTaskNum(), first.getTaskNum());
        }
    }

    @Test
    @DisplayName("接单时间同秒（MySQL 秒精度）时按 id 倒序，顺序稳定")
    void sameSecondAcceptTimeFallsBackToIdDesc() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        fixtures.bindDrone(rider.id(), UniqueNames.djiId(), fc30());
        Task first = paidTask(owner, rider);
        taskService.riderConfirmOrder(first.getTaskNum(), rider.id());
        Task second = paidTask(owner, rider);
        taskService.riderConfirmOrder(second.getTaskNum(), rider.id());

        LocalDateTime sameSecond = LocalDateTime.now().withNano(0);
        for (Task task : new Task[]{first, second}) {
            TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElseThrow();
            assignment.setAcceptTime(sameSecond);
            taskAssignmentRepository.save(assignment);
        }
        long firstAssignmentId = taskAssignmentRepository.findByTaskId(first.getId()).orElseThrow().getId();
        long secondAssignmentId = taskAssignmentRepository.findByTaskId(second.getId()).orElseThrow().getId();
        assertThat(secondAssignmentId).isGreaterThan(firstAssignmentId);

        for (String path : new String[]{"/rider/my-tasks", "/rider/my-tasks/history"}) {
            assertThat(taskNums(tasksOf(path, rider))).as(path)
                    .containsExactly(second.getTaskNum(), first.getTaskNum());
        }
    }

    @Test
    @DisplayName("列表批量装配：SQL 语句数与列表长度无关（无 N+1）")
    void listAssemblyHasNoNPlusOne() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        fixtures.bindDrone(rider.id(), UniqueNames.djiId(), fc30());
        Task one = paidTask(owner, rider);
        taskService.riderConfirmOrder(one.getTaskNum(), rider.id());

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            long withOne = statementsFor("/rider/my-tasks/history", rider, stats, 1);
            for (int i = 0; i < 3; i++) {
                Task more = paidTask(owner, rider);
                taskService.riderConfirmOrder(more.getTaskNum(), rider.id());
            }
            long withFour = statementsFor("/rider/my-tasks/history", rider, stats, 4);
            assertThat(withFour).as("1 单 %d 条 SQL，4 单不应更多", withOne).isEqualTo(withOne);

            long activeWithFour = statementsFor("/rider/my-tasks", rider, stats, 4);
            Task applying = publish(accounts().registerUser());
            taskApplicationService.apply(applying.getTaskNum(), rider.id(), fc30());
            assertThat(statementsFor("/rider/my-tasks", rider, stats, 5))
                    .as("进行中多一张应征中卡片不应多出 SQL").isEqualTo(activeWithFour);
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }
    }

    private long statementsFor(String path, TestAccounts.Account rider, Statistics stats, int expectedSize)
            throws Exception {
        // 用例事务内共享一级缓存：先落库并清空，统计的才是接口本身的 SQL
        entityManager.flush();
        entityManager.clear();
        stats.clear();
        JsonNode tasks = tasksOf(path, rider);
        long count = stats.getPrepareStatementCount();
        assertThat(tasks.size()).as(path).isEqualTo(expectedSize);
        return count;
    }

    private JsonNode detailOf(String taskNum, TestAccounts.Account rider) throws Exception {
        String body = mockMvc.perform(get("/rider/task/detail").param("taskNum", taskNum)
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).path("data");
    }

    private static void assertNoWinnerFields(JsonNode vo, String where) {
        assertThat(vo.path("totalAmount").isNull() || vo.path("totalAmount").isMissingNode())
                .as("%s：不回显成交价", where).isTrue();
        assertThat(vo.path("orderNum").isNull() || vo.path("orderNum").isMissingNode())
                .as("%s：不回显订单号", where).isTrue();
        assertThat(vo.path("scheduledTime").isNull() || vo.path("scheduledTime").isMissingNode())
                .as("%s：不回显约定时间", where).isTrue();
        assertThat(vo.path("riderId").isNull() || vo.path("riderId").isMissingNode())
                .as("%s：不回显接单飞手", where).isTrue();
        assertThat(vo.path("riderName").isNull() || vo.path("riderName").isMissingNode())
                .as("%s：不回显接单飞手昵称", where).isTrue();
    }

    @Test
    @DisplayName("隐私：落选飞手在列表与详情中只看到本人报价/机型，从不看到选定飞手的报价、机型与成交信息")
    void losingRiderNeverSeesWinnersQuote() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account winner = accounts().registerRider();
        TestAccounts.Account loser = accounts().registerRider();
        TestAccounts.Account bystander = accounts().registerRider();
        long m350 = aircraftModelRepository.findByModelCode("M350RTK").orElseThrow().getId();
        fixtures.bindDrone(winner.id(), UniqueNames.djiId(), fc30());
        fixtures.bindDrone(loser.id(), UniqueNames.djiId(), m350);
        fixtures.bindDrone(bystander.id(), UniqueNames.djiId(), fc30());

        Task task = publish(owner);
        taskApplicationService.apply(task.getTaskNum(), winner.id(), fc30());
        taskApplicationService.apply(task.getTaskNum(), loser.id(), m350);
        TaskApplication winnerApp = taskApplicationRepository
                .findByTaskIdAndRiderId(task.getId(), winner.id()).orElseThrow();
        TaskApplication loserApp = taskApplicationRepository
                .findByTaskIdAndRiderId(task.getId(), loser.id()).orElseThrow();
        assertThat(loserApp.getQuotedAmount()).as("前提：两个机型报价不同")
                .isNotEqualByComparingTo(winnerApp.getQuotedAmount());

        // 选定前：详情与列表都是本人报价
        JsonNode beforeSelect = detailOf(task.getTaskNum(), loser);
        assertThat(beforeSelect.path("quotedAmount").decimalValue())
                .isEqualByComparingTo(loserApp.getQuotedAmount());
        // 发单即生成草稿订单：选定前任何飞手都看不到订单号（列表与详情）
        assertNoWinnerFields(beforeSelect, "选定前·应征飞手详情");
        JsonNode applyingCard = java.util.stream.StreamSupport
                .stream(tasksOf("/rider/my-tasks", loser).spliterator(), false)
                .filter(t -> t.path("taskNum").asText().equals(task.getTaskNum())).findFirst().orElseThrow();
        assertThat(applyingCard.path("orderNum").isNull() || applyingCard.path("orderNum").isMissingNode())
                .as("选定前·应征中卡片不回显草稿订单号").isTrue();
        assertThat(orderRepository.findByTaskId(task.getId())).as("前提：草稿订单已存在").isPresent();

        taskService.selectRider(task.getTaskNum(), owner.id(), winnerApp.getId(), LocalDateTime.now().plusDays(1));
        fixtures.payLockedOrder(task);

        // 列表：落选应征（CLOSED）不在「进行中」，历史中也没有（无接单记录）
        assertThat(taskNums(tasksOf("/rider/my-tasks", loser))).doesNotContain(task.getTaskNum());
        assertThat(taskNums(tasksOf("/rider/my-tasks/history", loser))).doesNotContain(task.getTaskNum());

        for (boolean confirmed : new boolean[]{false, true}) {
            if (confirmed) {
                taskService.riderConfirmOrder(task.getTaskNum(), winner.id());
            }
            String phase = confirmed ? "选定飞手确认后" : "已选定并支付";
            JsonNode loserDetail = detailOf(task.getTaskNum(), loser);
            assertThat(loserDetail.path("quotedAmount").decimalValue()).as("%s：落选飞手只见本人报价", phase)
                    .isEqualByComparingTo(loserApp.getQuotedAmount())
                    .isNotEqualByComparingTo(winnerApp.getQuotedAmount());
            assertThat(loserDetail.path("aircraftModelId").asLong()).as(phase).isEqualTo(m350);
            assertNoWinnerFields(loserDetail, phase + "·落选飞手详情");

            JsonNode bystanderDetail = detailOf(task.getTaskNum(), bystander);
            assertThat(bystanderDetail.path("quotedAmount").isNull()
                    || bystanderDetail.path("quotedAmount").isMissingNode())
                    .as("%s：未应征飞手无报价", phase).isTrue();
            assertThat(bystanderDetail.path("aircraftModelId").isNull()
                    || bystanderDetail.path("aircraftModelId").isMissingNode()).as(phase).isTrue();
            assertNoWinnerFields(bystanderDetail, phase + "·未应征飞手详情");

            JsonNode winnerDetail = detailOf(task.getTaskNum(), winner);
            assertThat(winnerDetail.path("quotedAmount").decimalValue())
                    .isEqualByComparingTo(winnerApp.getQuotedAmount());
            assertThat(winnerDetail.path("totalAmount").decimalValue()).as("%s：选定飞手见成交价", phase)
                    .isEqualByComparingTo(winnerApp.getQuotedAmount());
            assertThat(winnerDetail.path("orderNum").asText()).isNotBlank();
        }
        JsonNode winnerActive = java.util.stream.StreamSupport
                .stream(tasksOf("/rider/my-tasks", winner).spliterator(), false)
                .filter(t -> t.path("taskNum").asText().equals(task.getTaskNum())).findFirst().orElseThrow();
        assertThat(winnerActive.path("riderName").asText()).isEqualTo(winner.userName());
    }
}

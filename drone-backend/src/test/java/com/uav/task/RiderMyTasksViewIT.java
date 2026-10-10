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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 飞手端「我的接单」列表（/rider/my-tasks、/rider/my-tasks/history）与详情同一装配：
 * 必须带订单号、成交价、订单状态、约定时间与成交机型，飞手端卡片才能显示成交价而不是「面议」；
 * 列表按接单时间倒序（最新在前）。
 */
class RiderMyTasksViewIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private TaskService taskService;
    @Autowired private TaskApplicationService taskApplicationService;
    @Autowired private TaskApplicationRepository taskApplicationRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private AircraftModelRepository aircraftModelRepository;

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
}

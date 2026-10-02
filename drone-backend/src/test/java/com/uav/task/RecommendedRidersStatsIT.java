package com.uav.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.server.enums.TaskStatus;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.vo.RiderStatsVO;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /rider/recommended} 批量统计（移植自 PR #14 {@code batchRiderStats}：一次 GROUP BY 替代 N×3 查询）。
 *
 * <p>断言口径与单飞手 {@code getRiderStats}（{@code GET /rider/stats} 同源）逐项一致：今日接单
 * （严格晚于今日零点）、累计完成、累计收益；无接单记录的飞手为 0/0/0.0（不为 null）；按完成数降序。
 * R2/R3/R7：继承 {@link IntegrationTestBase}，身份取自真实注册接口，任务走共享 fixture。
 */
class RecommendedRidersStatsIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("批量统计与单飞手 getRiderStats 逐项一致；无记录飞手为 0；按完成数降序")
    void batchStatsMatchPerRiderStats() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account busy = accounts().registerRider(UniqueNames.userName("busy"), null);
        TestAccounts.Account light = accounts().registerRider(UniqueNames.userName("light"), null);
        TestAccounts.Account idle = accounts().registerRider(UniqueNames.userName("idle"), null);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        // busy：今日接单并完成 2 单（reward 100 + 50.5）、昨日接单未完成 1 单（不计今日/完成/收益）
        assign(owner, busy, 100.0, LocalDateTime.now(), LocalDateTime.now());
        assign(owner, busy, 50.5, todayStart.plusSeconds(1), LocalDateTime.now());
        assign(owner, busy, 999.0, todayStart.minusHours(3), null);
        // light：昨日接单今日完成 1 单（计完成与收益，不计今日接单）；恰在零点接单的不计今日（严格大于）
        assign(owner, light, 20.0, todayStart.minusDays(1), LocalDateTime.now());
        assign(owner, light, 7.0, todayStart, null);

        String raw = mockMvc.perform(get("/rider/recommended").header("Authorization", busy.authorization()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = MAPPER.readTree(raw).path("data");

        Map<Long, JsonNode> byRider = new HashMap<>();
        list.forEach(row -> byRider.put(row.path("riderId").asLong(), row));
        for (TestAccounts.Account rider : List.of(busy, light, idle)) {
            JsonNode row = byRider.get(rider.id());
            assertThat(row).as("推荐列表应包含飞手 %s", rider.userName()).isNotNull();
            RiderStatsVO expected = taskService.getRiderStats(rider.id());
            assertThat(row.path("riderName").asText()).isEqualTo(rider.userName());
            assertThat(row.path("todayOrders").asLong()).as("todayOrders of %s", rider.userName())
                    .isEqualTo(expected.getTodayOrders());
            assertThat(row.path("totalCompleted").asLong()).as("totalCompleted of %s", rider.userName())
                    .isEqualTo(expected.getTotalCompleted());
            assertThat(row.path("totalEarnings").asDouble()).as("totalEarnings of %s", rider.userName())
                    .isEqualTo(expected.getTotalEarnings());
        }

        // 显式数值（防止两条口径同时漂移）
        assertThat(byRider.get(busy.id()).path("todayOrders").asLong()).isEqualTo(2);
        assertThat(byRider.get(busy.id()).path("totalCompleted").asLong()).isEqualTo(2);
        assertThat(byRider.get(busy.id()).path("totalEarnings").asDouble()).isEqualTo(150.5);
        assertThat(byRider.get(light.id()).path("todayOrders").asLong()).isZero();
        assertThat(byRider.get(light.id()).path("totalCompleted").asLong()).isEqualTo(1);
        assertThat(byRider.get(light.id()).path("totalEarnings").asDouble()).isEqualTo(20.0);
        JsonNode idleRow = byRider.get(idle.id());
        assertThat(idleRow.path("todayOrders").asLong()).isZero();
        assertThat(idleRow.path("totalCompleted").asLong()).isZero();
        assertThat(idleRow.path("totalEarnings").isNumber()).as("无记录飞手收益应为 0 而非 null").isTrue();
        assertThat(idleRow.path("totalEarnings").asDouble()).isZero();

        // 按累计完成数降序
        long previous = Long.MAX_VALUE;
        for (JsonNode row : list) {
            long completed = row.path("totalCompleted").asLong();
            assertThat(completed).isLessThanOrEqualTo(previous);
            previous = completed;
        }
    }

    private void assign(TestAccounts.Account owner, TestAccounts.Account rider, double reward,
                        LocalDateTime acceptTime, LocalDateTime completeTime) {
        Task task = fixtures.task(completeTime != null ? TaskStatus.COMPLETED : TaskStatus.IN_PROGRESS,
                reward, owner.id());
        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(task.getId());
        assignment.setRiderId(rider.id());
        assignment.setAcceptTime(acceptTime);
        assignment.setCompleteTime(completeTime);
        taskAssignmentRepository.save(assignment);
    }
}

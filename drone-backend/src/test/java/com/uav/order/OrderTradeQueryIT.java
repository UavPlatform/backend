package com.uav.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * App「我的交易」接口（{@code /order/stats}、{@code /order/trades}、{@code /order/sold}、
 * {@code /order/pending-review}）与 {@code /order/list} 的状态过滤。
 *
 * <p>卖方口径固定为订单上的成交指针 {@code selectedApplicationId}（ADR-0003 决定 3：用户选定应征时
 * 写入、重新选定会改指），<b>不是</b>履约记录 {@code task_assignment}（飞手取消接单会被删除）。
 * {@link #soldTabFollowsSelectedApplication} 专门锁死这一点。
 *
 * <p>R2/R3/R7：继承 {@link IntegrationTestBase}，身份取自真实注册接口，订单/任务走共享 fixture。
 */
class OrderTradeQueryIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("统计口径：累计订单含未完成状态，已完成交易只算「已完成」")
    void statsCountOnlyCompleted() throws Exception {
        TestAccounts.Account buyer = accounts().registerUser();

        orderIn(buyer, OrderStatus.MATCHING, "0.00");
        orderIn(buyer, OrderStatus.PENDING, "100.00");
        orderIn(buyer, OrderStatus.WAITING_CONFIRM, "200.00");
        orderIn(buyer, OrderStatus.COMPLETED, "300.00");

        JsonNode stats = statsOf(buyer);
        assertThat(stats.path("totalOrderCount").asLong()).as("累计订单数含全部状态").isEqualTo(4);
        assertThat(stats.path("completedTradeCount").asLong())
                .as("已完成交易数只算已完成：待撮合/待支付/待验收均不计").isEqualTo(1);
        assertThat(stats.path("boughtOrderCount").asLong()).isEqualTo(4);
        assertThat(stats.path("soldOrderCount").asLong()).isZero();
        assertThat(stats.path("publishedTaskCount").asLong()).isEqualTo(4);
        assertThat(stats.path("pendingReviewCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("待评价：已完成且未评价才在列表里；评价后移出，待验收的从未出现")
    void pendingReviewExcludesReviewedAndNonCompleted() throws Exception {
        TestAccounts.Account buyer = accounts().registerUser();
        MissionOrder completed = orderIn(buyer, OrderStatus.COMPLETED, "300.00");
        orderIn(buyer, OrderStatus.WAITING_CONFIRM, "200.00");

        assertThat(statsOf(buyer).path("pendingReviewCount").asLong()).isEqualTo(1);
        assertThat(orderNums(dataOf("/order/pending-review", buyer))).containsExactly(completed.getOrderNum());

        mockMvc.perform(post("/review/submit")
                        .param("orderNum", completed.getOrderNum())
                        .param("rating", "5")
                        .header("Authorization", buyer.authorization()))
                .andExpect(status().isOk());

        assertThat(statsOf(buyer).path("pendingReviewCount").asLong()).isZero();
        assertThat(orderNums(dataOf("/order/pending-review", buyer))).isEmpty();
    }

    @Test
    @DisplayName("「我卖出的」跟成交指针走：未被选定的履约记录不算卖出，重新选定后订单易主")
    void soldTabFollowsSelectedApplication() throws Exception {
        TestAccounts.Account buyer = accounts().registerUser();
        TestAccounts.Account first = accounts().registerRider(UniqueNames.userName("first"), null);
        TestAccounts.Account second = accounts().registerRider(UniqueNames.userName("second"), null);
        TestAccounts.Account assignee = accounts().registerRider(UniqueNames.userName("assignee"), null);

        Task task = fixtures.task(TaskStatus.IDLE, 0.0, buyer.id());
        fixtures.order(buyer.id(), task, OrderStatus.MATCHING, "0.00");
        MissionOrder order = fixtures.selectAndLock(task, first.id());

        // 只有履约记录、订单成交指针不指向他的飞手：不是「卖出」
        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(task.getId());
        assignment.setRiderId(assignee.id());
        assignment.setAcceptTime(LocalDateTime.now());
        taskAssignmentRepository.save(assignment);
        assertThat(orderNums(dataOf("/order/sold", assignee)))
                .as("仅有履约记录、未被选定，不应计入我卖出的").isEmpty();

        assertThat(orderNums(dataOf("/order/sold", first))).containsExactly(order.getOrderNum());
        assertThat(orderNums(dataOf("/order/sold", second))).isEmpty();

        // 用户重新选定另一名飞手 → 成交指针改指，订单从原飞手列表移出
        fixtures.selectAndLock(task, second.id());
        assertThat(orderNums(dataOf("/order/sold", first))).as("重新选定后原飞手不再持有").isEmpty();
        assertThat(orderNums(dataOf("/order/sold", second))).containsExactly(order.getOrderNum());
    }

    @Test
    @DisplayName("我的交易 = 买 ∪ 卖；状态过滤对两侧同时生效（并集与状态的括号不能写错）")
    void myTradesUnionsBuyerAndSellerWithStatusFilter() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("both"), null);

        // 作为买家
        MissionOrder bought = orderIn(rider, OrderStatus.COMPLETED, "300.00");
        // 作为卖家：一单已完成、一单待支付
        MissionOrder soldCompleted = soldOrder(rider, OrderStatus.COMPLETED, "400.00");
        MissionOrder soldPending = soldOrder(rider, OrderStatus.PENDING, "500.00");

        assertThat(orderNums(dataOf("/order/trades", rider)))
                .containsExactlyInAnyOrder(bought.getOrderNum(), soldCompleted.getOrderNum(),
                        soldPending.getOrderNum());
        assertThat(orderNums(dataOf("/order/trades?status=COMPLETED", rider)))
                .as("状态过滤必须同时作用于买家侧与卖家侧").containsExactlyInAnyOrder(
                        bought.getOrderNum(), soldCompleted.getOrderNum());
        assertThat(orderNums(dataOf("/order/trades?status=PENDING", rider)))
                .containsExactly(soldPending.getOrderNum());
        assertThat(statsOf(rider).path("totalOrderCount").asLong()).isEqualTo(3);
        assertThat(statsOf(rider).path("completedTradeCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("列表与统计只含调用者自己的订单；未登录一律 401")
    void listsAreScopedToCaller() throws Exception {
        TestAccounts.Account a = accounts().registerUser();
        TestAccounts.Account b = accounts().registerUser();
        MissionOrder mine = orderIn(a, OrderStatus.COMPLETED, "100.00");
        MissionOrder theirs = orderIn(b, OrderStatus.COMPLETED, "200.00");

        assertThat(orderNums(dataOf("/order/list", a))).containsExactly(mine.getOrderNum());
        assertThat(orderNums(dataOf("/order/list", b))).containsExactly(theirs.getOrderNum());
        assertThat(orderNums(dataOf("/order/trades", a))).containsExactly(mine.getOrderNum());
        assertThat(statsOf(a).path("totalOrderCount").asLong()).isEqualTo(1);
        assertThat(statsOf(a).path("completedTradeCount").asLong()).isEqualTo(1);

        mockMvc.perform(get("/order/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/order/trades")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/order/sold")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/order/pending-review")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("分页生效；status 接受枚举名与状态码；非法状态码 400 而非静默忽略")
    void paginationAndStatusParsing() throws Exception {
        TestAccounts.Account buyer = accounts().registerUser();
        MissionOrder first = orderIn(buyer, OrderStatus.COMPLETED, "300.00");
        MissionOrder second = orderIn(buyer, OrderStatus.COMPLETED, "301.00");
        orderIn(buyer, OrderStatus.PENDING, "100.00");

        JsonNode page0 = dataOf("/order/list?page=0&size=1", buyer);
        assertThat(page0.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(3);
        assertThat(page0.path("orders").size()).isEqualTo(1);
        assertThat(page0.path("currentPage").asInt()).isZero();

        assertThat(orderNums(dataOf("/order/list?status=COMPLETED", buyer)))
                .containsExactlyInAnyOrder(first.getOrderNum(), second.getOrderNum());
        assertThat(orderNums(dataOf("/order/list?status=4", buyer)))
                .as("状态码与枚举名等价").containsExactlyInAnyOrder(first.getOrderNum(), second.getOrderNum());
        assertThat(orderNums(dataOf("/order/list?status=PENDING", buyer))).hasSize(1);

        mockMvc.perform(get("/order/list").param("status", "999")
                        .header("Authorization", buyer.authorization()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/order/trades").param("status", "NOT_A_STATUS")
                        .header("Authorization", buyer.authorization()))
                .andExpect(status().isBadRequest());
    }

    // ---------- 辅助 ----------

    /** 造一张归属指定用户的订单（任务一并造出，用于「我发布的」计数）。 */
    private MissionOrder orderIn(TestAccounts.Account owner, OrderStatus status, String amount) {
        Task task = fixtures.task(TaskStatus.IDLE, 0.0, owner.id());
        return fixtures.order(owner.id(), task, status, amount);
    }

    /** 造一张「被指定飞手卖出」的订单：成交指针指向该飞手的应征。 */
    private MissionOrder soldOrder(TestAccounts.Account seller, OrderStatus status, String amount) {
        TestAccounts.Account buyer = accounts().registerUser();
        Task task = fixtures.task(TaskStatus.IDLE, 0.0, buyer.id());
        fixtures.order(buyer.id(), task, OrderStatus.MATCHING, "0.00");
        MissionOrder order = fixtures.selectAndLock(task, seller.id());
        order.setOrderStatus(status);
        return orderRepository.save(order);
    }

    private JsonNode statsOf(TestAccounts.Account account) throws Exception {
        return dataOf("/order/stats", account);
    }

    private JsonNode dataOf(String path, TestAccounts.Account account) throws Exception {
        String raw = mockMvc.perform(get(path).header("Authorization", account.authorization()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(raw).path("data");
    }

    private List<String> orderNums(JsonNode data) {
        List<String> nums = new ArrayList<>();
        data.path("orders").forEach(order -> nums.add(order.path("orderNum").asText()));
        return nums;
    }
}

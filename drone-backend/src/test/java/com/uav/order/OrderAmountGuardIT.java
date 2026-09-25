package com.uav.order;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单金额护栏测试（协商定价口径）。
 *
 * <p><b>定价 Intent（2026-09-25 修订）</b>：平台支持「用户与飞手协商定价」——
 * 挂牌价 = 协商价 ?: 平台参考价，其中参考价由服务端按 bill_config 计算
 * （起步价 + 里程费 + 重量阶梯费 + 夜间附加费），协商价由客户端在发布时传入。
 * 金额仍一律<b>服务端</b>产生，客户端的输入受<b>下限约束</b>
 * （不得低于参考价 × {@code MIN_NEGOTIATED_RATE}），因此「1 分钱买服务」不可行。
 *
 * <p><b>与旧口径的差异</b>：本类原先断言「客户端 reward 一律被忽略、订单金额
 * 恒等于距离 × 单价」。计费模块（bill_config 阶梯计价 + 协商定价）合入后，
 * 该策略已被取代：协商价合规时即为成交价，仅低于下限时才拒绝。
 * 详见 {@code docs/定价设计.md}。
 *
 * <p>边界：金额为 0 时仍拒绝下单（见 {@code OrderServiceImpl#createOrder}）；
 * 但按现有 bill_config（起步价 30 元 &gt; 0），单航点任务不再触发该分支。
 *
 * <p>归位说明（O1/P5）：本类原先位于 {@code security/}，但其断言的是<b>订单计价业务规则</b>，
 * 按模块归属迁至 {@code order/}；类名遵循 O5（表达被测对象，不带工单号）。
 *
 * <p>层次与驱动（O6/R2/R4/R9）：进程内集成测试，继承 {@link IntegrationTestBase}
 * （{@code MOCK} + {@code @AutoConfigureMockMvc} + {@code @Transactional}），不声明真实端口；
 * 本类不发起 HTTP 请求（直接调用服务），也没有鉴权断言，故不需要 token。
 *
 * <p>隔离与造数（R5/R7/R8）：事务回滚隔离（删除原手工清理与 {@code userIds} 列表）；
 * 用户与任务 DTO 走共享工厂（{@code fixtures.user(0)}、{@code fixtures.twoWaypointTask()}、
 * {@code fixtures.oneWaypointTask(...)}）；ThreadLocal 由基类 {@code @AfterEach} 统一清理。
 */
class OrderAmountGuardIT extends IntegrationTestBase {

    @Autowired
    private TaskService taskService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    @DisplayName("协商价低于参考价 50% 时下单被拒：客户端不能把价格压到参考价以下")
    void undercutRewardRejected() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = fixtures.twoWaypointTask();
        // 参考价 = 起步价 30 元（两航点距离 < 基础里程 3km，无重量费/夜间费），
        // 下限 = 30 × MIN_NEGOTIATED_RATE(0.5) = 15 元
        dto.setReward(14.99);

        assertThatThrownBy(() -> taskService.createTask(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协商价");
    }

    @Test
    @DisplayName("协商价合规时成交价 = 协商价（挂牌价优先于平台参考价）")
    void negotiatedPriceAccepted() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = fixtures.twoWaypointTask();
        dto.setReward(25.0);   // ≥ 下限 15 元

        Task saved = taskService.createTask(dto);
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();

        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(saved.getReward()).isEqualByComparingTo(25.00);
        // 参考价仍如实落库，供客户端展示成交价与参考价的差额
        assertThat(saved.getReferencePrice()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("reward 为 null（0 元下单尝试）也被服务端计价为正数金额")
    void nullRewardStillPriced() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = fixtures.twoWaypointTask();
        dto.setReward(null);

        Task saved = taskService.createTask(dto);

        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();
        assertThat(order.getTotalAmount()).isNotNull();
        assertThat(order.getTotalAmount().signum()).isPositive();
    }

    @Test
    @DisplayName("单航点任务按起步价计价，不再计为 0")
    void singleWaypointPricedAtBaseFee() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = fixtures.oneWaypointTask(UniqueNames.unique("single"));

        // 航点距离为 0，但参考价 = 起步价（30 元）仍为正 → 订单正常创建。
        // 旧口径下该场景计为 0 并被拒绝；bill_config 阶梯计价引入起步价后不再触发。
        Task saved = taskService.createTask(dto);
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();

        assertThat(order.getTotalAmount()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("PENDING 订单任务可删除（既有取消路径不受影响）")
    void deleteTaskWithUnpaidOrderAllowed() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        Task saved = taskService.createTask(fixtures.twoWaypointTask());
        taskService.deleteTask(saved.getId(), user.getId());

        assertThat(taskRepository.findById(saved.getId())).isEmpty();
    }
}

package com.uav.order;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.calculator.RoutePriceCalculator;
import com.uav.server.enums.OrderStatus;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-2 防护测试：订单金额一律由服务端按航点距离计算，客户端 reward 篡改无效；
 * 计价为 0 的订单（航点不足）被拒绝。
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
    @DisplayName("篡改 reward（99999 元）不影响订单金额：服务端按 0.05 元/米计价")
    void tamperedRewardIgnored() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = fixtures.twoWaypointTask();
        dto.setReward(99999.0);   // 客户端尝试把金额改成 99999 元

        Task saved = taskService.createTask(dto);

        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();
        BigDecimal expected = RoutePriceCalculator.calculatePrice(
                RoutePriceCalculator.calculateTotalDistance(saved.getWaypoints()),
                new BigDecimal("0.05"));

        assertThat(order.getTotalAmount()).isEqualByComparingTo(expected);
        assertThat(order.getTotalAmount().doubleValue()).isLessThan(1000.0);
        // 任务 reward 同步为服务端计价，客户端值不入库
        assertThat(saved.getReward()).isEqualByComparingTo(expected.doubleValue());
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
    @DisplayName("航点不足导致计价为 0 时下单被拒")
    void zeroAmountOrderRejected() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = fixtures.oneWaypointTask(UniqueNames.unique("single"));

        assertThatThrownBy(() -> taskService.createTask(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额");
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

package com.uav.order;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.service.impl.OrderAutoConfirmService;
import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.support.IntegrationTestBase;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.entity.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1B-9a 骨架测试：验收超时自动确认。
 * 默认关闭 → 零行为变化；开启 → 仅超时（update_time 早于阈值）的 WAITING_CONFIRM 订单被置为 COMPLETED，
 * 且任务撮合状态同步从 PENDING_ACCEPTANCE 结案为 CLOSED（ADR-0003 决定 5，与用户手动确认一致）。
 *
 * <p>层次与驱动（O6/R2/R4/R9）：进程内集成测试，继承 {@link IntegrationTestBase}
 * （{@code MOCK} + {@code @AutoConfigureMockMvc} + {@code @Transactional}），不声明真实端口。
 * 本类不发起 HTTP 请求（直接调用服务方法），基类提供的 MockMvc 只用于统一上下文、profile 与清理。
 *
 * <p>隔离（R5）：事务回滚；过期时间沿用既有 JPQL {@code forceUpdateTime} 直改（绕过 {@code @PreUpdate}）。
 * 造数走共享工厂（R7），ThreadLocal 由基类 {@code @AfterEach} 清理（R8），
 * 唯一命名由共享 {@code UniqueNames} 提供，不再以 {@code System.nanoTime()} 兜底。
 */
class AutoConfirmIT extends IntegrationTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private OrderAutoConfirmService autoConfirmService;

    /**
     * 造一个 WAITING_CONFIRM 订单（任务撮合状态置为待验收 PENDING_ACCEPTANCE，fixtures.task 默认
     * SEEKING_RIDER），并把 update_time 直改为指定时间以模拟过期/未过期。
     */
    private MissionOrder seedWaitingConfirmOrder(LocalDateTime updateTime) {
        var owner = fixtures.user(0);
        Task task = fixtures.task(owner, TaskStatus.COMPLETED, 9.9);
        task.setMatchStatus(MatchStatus.PENDING_ACCEPTANCE);
        // 必须立即 flush：随后的 forceUpdateTime（@Modifying clearAutomatically）会清空持久化上下文，
        // 未 flush 的撮合状态改动会被丢弃（实测断言回落 SEEKING_RIDER）
        taskRepository.saveAndFlush(task);
        MissionOrder order = fixtures.order(owner, task, OrderStatus.WAITING_CONFIRM, "9.90");

        // 用 JPQL 直改 update_time（绕过 @PreUpdate 的 now 覆盖），模拟过期时间
        orderRepository.forceUpdateTime(order.getId(), updateTime);
        return order;
    }

    @Test
    @DisplayName("开关关闭（默认）：执行自动确认零行为变化（订单与撮合状态均不变）")
    void disabledMeansZeroBehaviorChange() {
        MissionOrder order = seedWaitingConfirmOrder(LocalDateTime.now().minusHours(73));

        int changed = autoConfirmService.autoConfirmExpiredOrders();

        assertThat(changed).isZero();
        MissionOrder after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getOrderStatus()).isEqualTo(OrderStatus.WAITING_CONFIRM);
        assertThat(after.getExecuteResult()).isNull();
        assertThat(after.getTask().getMatchStatus()).isEqualTo(MatchStatus.PENDING_ACCEPTANCE);
    }

    @Test
    @DisplayName("开关开启：仅超时订单被自动确认并留痕 AUTO_CONFIRM，撮合状态结案 CLOSED")
    void enabledAutoConfirmsExpiredOnly() {
        MissionOrder expired = seedWaitingConfirmOrder(LocalDateTime.now().minusHours(73));
        MissionOrder fresh = seedWaitingConfirmOrder(LocalDateTime.now());

        int changed = autoConfirmService.autoConfirmExpiredOrders(true, 72);

        assertThat(changed).isEqualTo(1);
        MissionOrder afterExpired = orderRepository.findById(expired.getId()).orElseThrow();
        assertThat(afterExpired.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(afterExpired.getExecuteResult()).isEqualTo("AUTO_CONFIRM");
        // 超时订单对应任务：撮合状态从 PENDING_ACCEPTANCE 结案为 CLOSED
        assertThat(afterExpired.getTask().getMatchStatus()).isEqualTo(MatchStatus.CLOSED);
        MissionOrder afterFresh = orderRepository.findById(fresh.getId()).orElseThrow();
        assertThat(afterFresh.getOrderStatus()).isEqualTo(OrderStatus.WAITING_CONFIRM);
        // 未过期订单对应任务：撮合状态保持待验收
        assertThat(afterFresh.getTask().getMatchStatus()).isEqualTo(MatchStatus.PENDING_ACCEPTANCE);
    }

    @Test
    @DisplayName("开关开启：未超时的 WAITING_CONFIRM 不受影响")
    void enabledDoesNotTouchFreshOrders() {
        MissionOrder fresh = seedWaitingConfirmOrder(LocalDateTime.now());

        int changed = autoConfirmService.autoConfirmExpiredOrders(true, 72);

        assertThat(changed).isZero();
        assertThat(orderRepository.findById(fresh.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.WAITING_CONFIRM);
    }
}

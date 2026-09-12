package com.uav.order;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.service.impl.OrderAutoConfirmService;
import com.uav.server.enums.OrderStatus;
import com.uav.task.pojo.entity.Task;
import com.uav.task.mapper.TaskRepository;
import com.uav.server.enums.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1B-9a 骨架测试：验收超时自动确认。
 * 默认关闭 → 零行为变化；开启 → 仅超时（update_time 早于阈值）的 WAITING_CONFIRM 订单被置为 COMPLETED。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class AutoConfirmTest {

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    OrderAutoConfirmService autoConfirmService;

    private long rid;

    @BeforeEach
    void setUp() {
        rid = System.nanoTime();
    }

    private MissionOrder seedWaitingConfirmOrder(LocalDateTime updateTime) {
        Task task = new Task();
        task.setTaskNum("AC-" + rid + "-" + updateTime.toLocalTime().toNanoOfDay());
        task.setTaskName("ac-task-" + rid);
        task.setUserId(1L);
        task.setTaskStatus(TaskStatus.COMPLETED);
        task = taskRepository.save(task);

        MissionOrder order = new MissionOrder();
        order.setOrderNum("ON-AC-" + rid + "-" + updateTime.toLocalTime().toNanoOfDay());
        order.setUserId(1L);
        order.setTask(task);
        order.setTotalAmount(new java.math.BigDecimal("9.90"));
        order.setTotalDistance(new java.math.BigDecimal("198.00"));
        order.setOrderStatus(OrderStatus.WAITING_CONFIRM);
        order = orderRepository.save(order);

        // 用 JPQL 直改 update_time（绕过 @PreUpdate 的 now 覆盖），模拟过期时间
        orderRepository.forceUpdateTime(order.getId(), updateTime);
        return order;
    }

    @Test
    @DisplayName("开关关闭（默认）：执行自动确认零行为变化")
    void disabledMeansZeroBehaviorChange() {
        MissionOrder order = seedWaitingConfirmOrder(LocalDateTime.now().minusHours(73));

        int changed = autoConfirmService.autoConfirmExpiredOrders();

        assertThat(changed).isZero();
        MissionOrder after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getOrderStatus()).isEqualTo(OrderStatus.WAITING_CONFIRM);
        assertThat(after.getExecuteResult()).isNull();
    }

    @Test
    @DisplayName("开关开启：仅超时订单被自动确认并留痕 AUTO_CONFIRM")
    void enabledAutoConfirmsExpiredOnly() {
        MissionOrder expired = seedWaitingConfirmOrder(LocalDateTime.now().minusHours(73));
        MissionOrder fresh = seedWaitingConfirmOrder(LocalDateTime.now());

        int changed = autoConfirmService.autoConfirmExpiredOrders(true, 72);

        assertThat(changed).isEqualTo(1);
        MissionOrder afterExpired = orderRepository.findById(expired.getId()).orElseThrow();
        assertThat(afterExpired.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(afterExpired.getExecuteResult()).isEqualTo("AUTO_CONFIRM");
        MissionOrder afterFresh = orderRepository.findById(fresh.getId()).orElseThrow();
        assertThat(afterFresh.getOrderStatus()).isEqualTo(OrderStatus.WAITING_CONFIRM);
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

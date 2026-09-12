package com.uav.task;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-8 防护测试：任务存在已支付/已完成订单时禁止删除，防止财务记录被物理删除。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class TaskDeleteGuardTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    TaskService taskService;

    private long rid;

    private int userSeq;

    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("含 PAID 订单的任务删除被拒，订单与任务保留")
    void deleteTaskWithPaidOrderRejected() {
        rid = System.nanoTime();
        User user = newUser();
        Task task = newTask(user);
        MissionOrder order = newOrder(user, task, OrderStatus.PAID);

        assertThatThrownBy(() -> taskService.deleteTask(task.getId(), user.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止删除");

        assertThat(taskRepository.findById(task.getId())).isPresent();
        assertThat(orderRepository.findById(order.getId())).isPresent();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("含 COMPLETED 订单的任务删除被拒")
    void deleteTaskWithCompletedOrderRejected() {
        rid = System.nanoTime();
        User user = newUser();
        Task task = newTask(user);
        newOrder(user, task, OrderStatus.COMPLETED);

        assertThatThrownBy(() -> taskService.deleteTask(task.getId(), user.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止删除");

        assertThat(taskRepository.findById(task.getId())).isPresent();
    }

    @Test
    @DisplayName("含 WAITING_CONFIRM 订单（已支付待确认）的任务删除被拒")
    void deleteTaskWithWaitingConfirmOrderRejected() {
        rid = System.nanoTime();
        User user = newUser();
        Task task = newTask(user);
        newOrder(user, task, OrderStatus.WAITING_CONFIRM);

        assertThatThrownBy(() -> taskService.deleteTask(task.getId(), user.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止删除");
    }

    @Test
    @DisplayName("他人删除他人任务仍然 403（既有归属校验保留）")
    void deleteOthersTaskForbidden() {
        rid = System.nanoTime();
        User owner = newUser();
        User attacker = newUser();
        Task task = newTask(owner);
        newOrder(owner, task, OrderStatus.PENDING);

        assertThatThrownBy(() -> taskService.deleteTask(task.getId(), attacker.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    // ---------- helpers ----------

    private User newUser() {
        User user = new User();
        user.setUserName("del" + rid + "-" + userSeq++);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(0);
        return userRepository.save(user);
    }

    private Task newTask(User owner) {
        Task task = new Task();
        task.setTaskNum("TN-DEL-" + rid);
        task.setTaskName("del-task-" + rid);
        task.setUserId(owner.getId());
        task.setTaskStatus(TaskStatus.IDLE);
        task.setReward(9.9);
        return taskRepository.save(task);
    }

    private MissionOrder newOrder(User owner, Task task, OrderStatus status) {
        MissionOrder order = new MissionOrder();
        order.setOrderNum("ON-DEL-" + rid + "-" + status.name());
        order.setUserId(owner.getId());
        order.setTask(task);
        order.setTotalAmount(new BigDecimal("9.90"));
        order.setTotalDistance(new BigDecimal("198.00"));
        order.setOrderStatus(status);
        order.setCreateTime(LocalDateTime.now());
        return orderRepository.save(order);
    }
}

package com.uav.security;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.calculator.RoutePriceCalculator;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskType;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.dto.WaypointDto;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-2 防护测试：订单金额一律由服务端按航点距离计算，客户端 reward 篡改无效；
 * 计价为 0 的订单（航点不足）被拒绝。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class OrderAmountGuardTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    TaskService taskService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    TaskRepository taskRepository;

    private final List<Long> userIds = new ArrayList<>();

    private long rid;

    @AfterEach
    void restoreContext() {
        UserContext.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("篡改 reward（99999 元）不影响订单金额：服务端按 0.05 元/米计价")
    void tamperedRewardIgnored() {
        rid = System.nanoTime();
        User user = newUser();
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = twoWaypointTask();
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
        rid = System.nanoTime();
        User user = newUser();
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = twoWaypointTask();
        dto.setReward(null);

        Task saved = taskService.createTask(dto);

        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();
        assertThat(order.getTotalAmount()).isNotNull();
        assertThat(order.getTotalAmount().signum()).isPositive();
    }

    @Test
    @DisplayName("航点不足导致计价为 0 时下单被拒")
    void zeroAmountOrderRejected() {
        rid = System.nanoTime();
        User user = newUser();
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = new TaskDto();
        dto.setTaskName("single-" + rid);
        dto.setType(TaskType.SURVEY);
        WaypointDto only = new WaypointDto();
        only.setOrderIndex(0);
        only.setLongitude(121.0);
        only.setLatitude(31.0);
        only.setAltitude(100.0);
        dto.setWaypoints(List.of(only));

        assertThatThrownBy(() -> taskService.createTask(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额");
    }

    @Test
    @DisplayName("PENDING 订单任务可删除（既有取消路径不受影响）")
    void deleteTaskWithUnpaidOrderAllowed() {
        rid = System.nanoTime();
        User user = newUser();
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        Task saved = taskService.createTask(twoWaypointTask());
        taskService.deleteTask(saved.getId(), user.getId());

        assertThat(taskRepository.findById(saved.getId())).isEmpty();
    }

    // ---------- helpers ----------

    private User newUser() {
        User user = new User();
        user.setUserName("amt" + rid);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(0);
        user = userRepository.save(user);
        userIds.add(user.getId());
        return user;
    }

    private TaskDto twoWaypointTask() {
        TaskDto dto = new TaskDto();
        dto.setTaskName("amt-task-" + rid);
        dto.setType(TaskType.SURVEY);
        dto.setReward(9.9);

        WaypointDto a = new WaypointDto();
        a.setOrderIndex(0);
        a.setLongitude(121.0);
        a.setLatitude(31.0);
        a.setAltitude(100.0);

        WaypointDto b = new WaypointDto();
        b.setOrderIndex(1);
        b.setLongitude(121.01);
        b.setLatitude(31.0);
        b.setAltitude(100.0);

        dto.setWaypoints(List.of(a, b));
        return dto;
    }
}

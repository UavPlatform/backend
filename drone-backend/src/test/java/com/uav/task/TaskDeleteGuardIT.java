package com.uav.task;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import com.uav.task.service.TaskService;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 防护测试（P0-8 / ADR-0003 更新）：任务存在<b>财务订单</b>时禁止删除，防止财务记录被物理删除。
 * 禁删状态：PAID / WAITING_CONFIRM / COMPLETED / REFUNDED / <b>DISPUTED</b>（争议处理中，同属财务记录）；
 * 非财务状态 MATCHING（草稿待撮合）/ PENDING（选定未支付）/ CANCELLED 允许删除——删除时订单行连带清理，
 * 且先清理 task_application（V3 的 fk_task_application_task FK 不再挡删除）。删除时任务执行中（IN_PROGRESS）
 * 亦拒绝。MockMvc 集成测试（R9/O4）：删除守卫走 Service→Repository 链路，必须启动 Spring。
 *
 * <p>R2：继承 {@link IntegrationTestBase}（MOCK + {@code @Transactional} 回滚隔离）；
 * R3：用户身份由 {@link TestAccounts} 真实注册取得（本用例只需合法 userId）；
 * R7：任务与订单由共享工厂 {@code TestFixtures} 直接造数；
 * R5：原 {@code System.nanoTime()} 唯一名与手工 {@code userRepository} 清理已删除，
 * 隔离改由事务回滚 + {@code UniqueNames} 保证。
 */
class TaskDeleteGuardIT extends IntegrationTestBase {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TaskApplicationRepository taskApplicationRepository;

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("含 PAID 订单的任务删除被拒，订单与任务保留")
    void deleteTaskWithPaidOrderRejected() {
        TestAccounts.Account user = accounts().registerUser();
        Task task = fixtures.task(TaskStatus.IDLE, 9.9, user.id());
        MissionOrder order = fixtures.order(user.id(), task, OrderStatus.PAID, "9.90");

        assertThatThrownBy(() -> taskService.deleteTask(task.getId(), user.id()))
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
        TestAccounts.Account user = accounts().registerUser();
        Task task = fixtures.task(TaskStatus.IDLE, 9.9, user.id());
        fixtures.order(user.id(), task, OrderStatus.COMPLETED, "9.90");

        assertThatThrownBy(() -> taskService.deleteTask(task.getId(), user.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止删除");

        assertThat(taskRepository.findById(task.getId())).isPresent();
    }

    @Test
    @DisplayName("含 WAITING_CONFIRM 订单（已支付待确认）的任务删除被拒")
    void deleteTaskWithWaitingConfirmOrderRejected() {
        TestAccounts.Account user = accounts().registerUser();
        Task task = fixtures.task(TaskStatus.IDLE, 9.9, user.id());
        fixtures.order(user.id(), task, OrderStatus.WAITING_CONFIRM, "9.90");

        assertThatThrownBy(() -> taskService.deleteTask(task.getId(), user.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止删除");
    }

    @Test
    @DisplayName("他人删除他人任务仍然 403（既有归属校验保留）")
    void deleteOthersTaskForbidden() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account attacker = accounts().registerUser();
        Task task = fixtures.task(TaskStatus.IDLE, 9.9, owner.id());
        fixtures.order(owner.id(), task, OrderStatus.PENDING, "9.90");

        assertThatThrownBy(() -> taskService.deleteTask(task.getId(), attacker.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    @Test
    @DisplayName("含草稿订单（MATCHING）与应征记录的任务可删除：应征/订单连带清理，FK 不再挡删除")
    void deleteTaskWithDraftOrderAndApplicationAllowed() {
        User owner = fixtures.user(0);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);

        // 发单走生产链路：任务 + MATCHING 草稿订单（非财务，允许删除）
        Task task = taskService.createTask(fixtures.twoWaypointTask());
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.MATCHING);

        // 再落一条飞手应征（task_application 带 fk_task_application_task，删除任务前必须先清理）
        TaskApplication application = fixtures.taskApplication(task, fixtures.rider().getId());

        taskService.deleteTask(task.getId(), owner.getId());

        assertThat(taskRepository.findById(task.getId())).isEmpty();
        assertThat(orderRepository.findById(order.getId())).isEmpty();
        assertThat(taskApplicationRepository.findById(application.getId())).isEmpty();
    }
}

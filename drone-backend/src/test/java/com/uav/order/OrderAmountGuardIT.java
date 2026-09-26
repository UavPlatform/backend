package com.uav.order;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2 防护测试（ADR-0003 改版）：发单只创建 {@code MATCHING} 草稿订单，金额一律为 0（未锁定），
 * 客户端 reward 只作参考值入库、不成为金额来源；同一用户可并行发布多个需求。
 *
 * <p><b>语义变更说明</b>：原「服务端按航点距离计价、reward 篡改无效、计价为 0 拒绝发单」的断言
 * 随计价点迁移已并入「用户选定应征」链路——金额锁定点 = {@code selectRider}（totalAmount 严格
 * 等于 quotedAmount），正向金额锁定由 {@code MatchFlowIT} 覆盖，改价被拒由 {@code MockPayFlowIT}
 * 覆盖（/pay 与 handleNotify 双硬校验 {@code AMOUNT_MISMATCH}）；因此原 {@code zeroAmountOrderRejected}
 * （航点不足计价为 0 拒绝发单）用例删除：发单已不计价，不存在 0 金额拒绝路径。
 *
 * <p>归位说明（O1/P5）：本类原先位于 {@code security/}，但其断言的是<b>订单金额防护业务规则</b>，
 * 按模块归属迁至 {@code order/}；类名遵循 O5（表达被测对象，不带工单号）。
 *
 * <p>层次与驱动（O6/R2/R4/R9）：进程内集成测试，继承 {@link IntegrationTestBase}
 * （{@code MOCK} + {@code @AutoConfigureMockMvc} + {@code @Transactional}），不声明真实端口；
 * 本类不发起 HTTP 请求（直接调用服务），也没有鉴权断言，故不需要 token。
 *
 * <p>隔离与造数（R5/R7/R8）：事务回滚隔离；用户与任务 DTO 走共享工厂
 * （{@code fixtures.user(0)}、{@code fixtures.twoWaypointTask()}）；ThreadLocal 由基类
 * {@code @AfterEach} 统一清理。
 */
class OrderAmountGuardIT extends IntegrationTestBase {

    @Autowired
    private TaskService taskService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    @DisplayName("篡改 reward（99999 元）不产生金额：发单只建 MATCHING 草稿订单，totalAmount=0")
    void clientRewardIgnoredAtCreation() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = fixtures.twoWaypointTask();
        dto.setReward(99999.0);   // 客户端尝试把金额改成 99999 元

        Task saved = taskService.createTask(dto);

        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.MATCHING);
        // 金额锁定点已迁到「用户选定应征」（selectRider）：发单时 totalAmount 恒为 0（未锁定），
        // 客户端 reward 只作参考值保存，不再成为金额来源；最终成交价 = quotedAmount 由 MatchFlowIT 覆盖。
        assertThat(order.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("同一用户连建 3 个任务：3 张 MATCHING 草稿订单都存在且互不冲突")
    void multipleDraftOrdersAllowed() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        Task first = taskService.createTask(fixtures.twoWaypointTask());
        Task second = taskService.createTask(fixtures.twoWaypointTask());
        Task third = taskService.createTask(fixtures.twoWaypointTask());

        MissionOrder o1 = orderRepository.findByTaskId(first.getId()).orElseThrow();
        MissionOrder o2 = orderRepository.findByTaskId(second.getId()).orElseThrow();
        MissionOrder o3 = orderRepository.findByTaskId(third.getId()).orElseThrow();

        assertThat(o1.getOrderStatus()).isEqualTo(OrderStatus.MATCHING);
        assertThat(o2.getOrderStatus()).isEqualTo(OrderStatus.MATCHING);
        assertThat(o3.getOrderStatus()).isEqualTo(OrderStatus.MATCHING);
        // 冲突点 1：pending_key 单例约束已放开——MATCHING 不占用 pending_key（仅 PENDING 占用），
        // 同一用户可并行发布多个需求而不触发唯一约束。
        assertThat(o1.getPendingKey()).isNull();
        assertThat(o2.getPendingKey()).isNull();
        assertThat(o3.getPendingKey()).isNull();
        assertThat(List.of(o1.getOrderNum(), o2.getOrderNum(), o3.getOrderNum()))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("reward 为 null 仍创建成功：MATCHING 草稿订单、金额 0")
    void nullRewardStillCreatesDraftOrder() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        TaskDto dto = fixtures.twoWaypointTask();
        dto.setReward(null);

        Task saved = taskService.createTask(dto);

        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.MATCHING);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("MATCHING 草稿订单任务可删除（订单行连带删除，既有取消路径不受影响）")
    void deleteTaskWithUnpaidOrderAllowed() {
        User user = fixtures.user(0);
        UserContext.setUser(user.getId(), user.getUserName(), 0);

        Task saved = taskService.createTask(fixtures.twoWaypointTask());
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();
        taskService.deleteTask(saved.getId(), user.getId());

        assertThat(taskRepository.findById(saved.getId())).isEmpty();
        assertThat(orderRepository.findById(order.getId())).isEmpty();
    }
}

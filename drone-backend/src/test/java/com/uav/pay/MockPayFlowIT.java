package com.uav.pay;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.pay.mapper.PayRecordRepository;
import com.uav.pay.pojo.entity.PayRecord;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.PayNotifyException;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-1a mock 支付链路集成测试（进程内 MockMvc，<b>不是端到端测试</b>）。
 * /pay/{orderNum}（mock 态，可省略 openid）→ pay_record 建 PENDING 流水 →
 * 复用真实 handleNotify 状态机（含 t3 金额比对）→ 订单 PAID + 流水 PAID。
 *
 * <p>层次自称（O6/P6）：本类经 MockMvc 在测试线程内同步调用 DispatcherServlet，真实 HTTP
 * 基础设施为 0，故既往「端到端测试」的表述已修正为「集成测试」；命名按 O4/R9 为 {@code *IT}。
 *
 * <p>层次与驱动（R2/R4）：继承 {@link IntegrationTestBase}（{@code MOCK} + {@code @AutoConfigureMockMvc}）
 * 取得上下文、MockMvc、共享工厂与 ThreadLocal 清理，不声明真实端口；本类显式声明
 * {@code @SpringBootTest(properties = ...)} 以打开应用自带的 mock 支付开关并改用独立内存库
 * （避免与共享上下文的 create-drop 互相清库），因此会单独缓存一份上下文。
 *
 * <p><b>R5 事务策略（本类唯一与基类默认不同之处，显式声明、有实测依据）</b>：
 * 本类声明 {@code @Transactional(propagation = PROPAGATION_NOT_SUPPORTED)}，即<b>退出</b>基类继承的
 * 测试事务、<b>真实提交</b>运行——这是迁移前该类本就有的语义（迁移前无 {@code @Transactional}，
 * 并自带独立内存库）。原因是 test 3 的断言依赖「另一条独立事务（{@code PayRecordAuditService}
 * 的 {@code REQUIRES_NEW}）写入并提交后，再以新的持久化上下文读到该值」：
 * 在外层测试事务里，Hibernate 一级缓存会缓存已加载的 {@code PayRecord}，审计事务提交的
 * {@code errorMsg} 不会被后续 {@code findByOrderNum} 覆盖，断言会失败（已实测：断言落在
 * {@code errorMsg} 为 null）。它不是「afterCommit 通知 / 异步产物」类断言，因此不适用 R5 的
 * {@link com.uav.support.RealProtocolTestBase} 例外，但同样必须真实提交。
 *
 * <p>R5 隔离方式（无回滚）：独立内存库 + {@link TestAccounts} 唯一用户名/身份 +
 * 共享工厂唯一命名；因此每轮运行都写真实提交的数据，但互不冲突，也不再以
 * {@code System.nanoTime()} 兜底（原 {@code rid} 已由 {@link UniqueNames} 取代）。
 *
 * <p>身份来源（R3）：真实注册/登录取合法 token（{@link TestAccounts}），不再自签 JWT 绕过认证链路。
 */
@SpringBootTest(properties = {
        "wechat.pay.mock-enabled=true",
        // 独立内存库：避免与共享上下文的 create-drop 互相清库
        "spring.datasource.url=jdbc:h2:mem:drone_mockpay_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MockPayFlowIT extends IntegrationTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PayRecordRepository payRecordRepository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private com.uav.pay.service.WeChatPayService weChatPayService;

    @Test
    @DisplayName("mock 支付全链路：/pay 无 openid 可支付 → 订单 PENDING→PAID、流水正确")
    void mockPayCompletesOrderAndRecord() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        UserContext.setUser(user.id(), user.userName(), user.role());
        var saved = taskService.createTask(fixtures.twoWaypointTask());
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);

        mockMvc.perform(post("/pay/" + order.getOrderNum())
                        .header("Authorization", user.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderNum").value(order.getOrderNum()))
                .andExpect(jsonPath("$.data.prepayId").value("mock-prepay-" + order.getOrderNum()));

        // 订单终态
        MissionOrder after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getOrderStatus()).isEqualTo(OrderStatus.PAID);

        // pay_record 流水正确
        PayRecord record = payRecordRepository.findByOrderNum(order.getOrderNum()).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(record.getAmount()).isEqualByComparingTo(order.getTotalAmount());
        assertThat(record.getTransactionId()).startsWith("mock-");
        assertThat(record.getPrepayId()).isEqualTo("mock-prepay-" + order.getOrderNum());
        assertThat(record.getPayChannel()).isEqualTo("WECHAT");
        assertThat(record.getPayTime()).isNotNull();
    }

    @Test
    @DisplayName("已支付订单再次发起支付被拒（幂等防护，既有状态机不受 mock 影响）")
    void payAgainRejected() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        UserContext.setUser(user.id(), user.userName(), user.role());
        var saved = taskService.createTask(fixtures.twoWaypointTask());
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();

        mockMvc.perform(post("/pay/" + order.getOrderNum())
                        .header("Authorization", user.authorization()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/pay/" + order.getOrderNum())
                        .header("Authorization", user.authorization()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("t3 金额比对在 mock 链路同样生效：金额不符拒绝入账、订单保持 PENDING")
    void amountComparisonActiveInMockChain() {
        TestAccounts.Account user = accounts().registerUser();
        UserContext.setUser(user.id(), user.userName(), user.role());
        var saved = taskService.createTask(fixtures.twoWaypointTask());
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();

        // 模拟 /pay 已创建的 PENDING 流水（pay() 的前置步骤）
        PayRecord record = new PayRecord();
        record.setOrderNum(order.getOrderNum());
        record.setUserId(user.id());
        record.setAmount(order.getTotalAmount());
        record.setPayChannel("WECHAT");
        record.setStatus(OrderStatus.PENDING);
        payRecordRepository.save(record);

        // mock 通道传入错误金额（应付金额 +1 分）→ 复用真实 handleNotify → 必须拒绝
        int expectedCents = order.getTotalAmount().multiply(java.math.BigDecimal.valueOf(100)).intValueExact();
        assertThatThrownBy(() -> weChatPayService.handleNotify(
                        UniqueNames.unique("mock-wrong"), order.getOrderNum(), "SUCCESS", expectedCents + 1))
                .isInstanceOf(PayNotifyException.class);

        MissionOrder after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        PayRecord afterRecord = payRecordRepository.findByOrderNum(order.getOrderNum()).orElseThrow();
        assertThat(afterRecord.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(afterRecord.getErrorMsg()).contains("金额不符");
    }
}

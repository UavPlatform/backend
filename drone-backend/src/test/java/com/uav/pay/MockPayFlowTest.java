package com.uav.pay;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.pay.mapper.PayRecordRepository;
import com.uav.pay.pojo.entity.PayRecord;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskType;
import com.uav.server.exception.PayNotifyException;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.dto.WaypointDto;
import com.uav.task.service.TaskService;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-1a 端到端测试：mock 支付链路全流程。
 * /pay/{orderNum}（mock 态，可省略 openid）→ pay_record 建 PENDING 流水 →
 * 复用真实 handleNotify 状态机（含 t3 金额比对）→ 订单 PAID + 流水 PAID。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "wechat.pay.mock-enabled=true",
        // 独立内存库：避免与共享上下文的 create-drop 互相清库
        "spring.datasource.url=jdbc:h2:mem:drone_mockpay_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class MockPayFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PayRecordRepository payRecordRepository;

    @Autowired
    TaskService taskService;

    @Autowired
    com.uav.pay.service.WeChatPayService weChatPayService;

    MockMvc mockMvc;

    private long rid;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rid = System.nanoTime();
    }

    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("mock 支付全链路：/pay 无 openid 可支付 → 订单 PENDING→PAID、流水正确")
    void mockPayCompletesOrderAndRecord() throws Exception {
        User user = newUser();
        UserContext.setUser(user.getId(), user.getUserName(), 0);
        TaskDto dto = twoWaypointTask();
        var saved = taskService.createTask(dto);
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);

        mockMvc.perform(post("/pay/" + order.getOrderNum())
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(user.getId(), user.getUserName(), 0)))
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
        User user = newUser();
        UserContext.setUser(user.getId(), user.getUserName(), 0);
        var saved = taskService.createTask(twoWaypointTask());
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();

        mockMvc.perform(post("/pay/" + order.getOrderNum())
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(user.getId(), user.getUserName(), 0)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/pay/" + order.getOrderNum())
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(user.getId(), user.getUserName(), 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("t3 金额比对在 mock 链路同样生效：金额不符拒绝入账、订单保持 PENDING")
    void amountComparisonActiveInMockChain() {
        User user = newUser();
        UserContext.setUser(user.getId(), user.getUserName(), 0);
        var saved = taskService.createTask(twoWaypointTask());
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElseThrow();

        // 模拟 /pay 已创建的 PENDING 流水（pay() 的前置步骤）
        PayRecord record = new PayRecord();
        record.setOrderNum(order.getOrderNum());
        record.setUserId(user.getId());
        record.setAmount(order.getTotalAmount());
        record.setPayChannel("WECHAT");
        record.setStatus(OrderStatus.PENDING);
        payRecordRepository.save(record);

        // mock 通道传入错误金额（应付金额 +1 分）→ 复用真实 handleNotify → 必须拒绝
        int expectedCents = order.getTotalAmount().multiply(java.math.BigDecimal.valueOf(100)).intValueExact();
        assertThatThrownBy(() -> weChatPayService.handleNotify(
                        "mock-wrong-" + rid, order.getOrderNum(), "SUCCESS", expectedCents + 1))
                .isInstanceOf(PayNotifyException.class);

        MissionOrder after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        PayRecord afterRecord = payRecordRepository.findByOrderNum(order.getOrderNum()).orElseThrow();
        assertThat(afterRecord.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(afterRecord.getErrorMsg()).contains("金额不符");
    }

    // ---------- helpers ----------

    private User newUser() {
        User user = new User();
        user.setUserName("pay" + rid);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(0);
        return userRepository.save(user);
    }

    private TaskDto twoWaypointTask() {
        TaskDto dto = new TaskDto();
        dto.setTaskName("pay-task-" + rid);
        dto.setType(TaskType.SURVEY);

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

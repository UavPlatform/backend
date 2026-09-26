package com.uav.pay;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.pay.mapper.PayRecordRepository;
import com.uav.pay.pojo.entity.PayRecord;
import com.uav.pay.service.impl.PayRecordAuditService;
import com.uav.pay.service.impl.WeChatPayServiceImpl;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.PayNotifyException;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.pojo.entity.TaskApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-3 防护测试：支付回调金额必须与订单应支付金额一致，不符拒绝入账并留痕。
 * ADR-0003「不允许改价」：入账前硬校验订单 totalAmount == 选定应征 quotedAmount，不一致拒绝入账并留痕。
 * 留痕经 PayRecordAuditService 以 REQUIRES_NEW 独立事务写入（主事务回滚后审计仍存活）。
 * 纯 Mockito 单元测试，不起 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
class PayNotifyAmountTest {

    private static final long RECORD_ID = 42L;

    @Mock
    OrderRepository orderRepository;

    @Mock
    PayRecordRepository payRecordRepository;

    @Mock
    PayRecordAuditService payRecordAuditService;

    @Mock
    TaskApplicationRepository taskApplicationRepository;

    @InjectMocks
    WeChatPayServiceImpl service;

    private static final String ORDER_NUM = "ON-TEST-1";

    private PayRecord pendingRecord() {
        PayRecord record = new PayRecord();
        record.setId(RECORD_ID);
        record.setOrderNum(ORDER_NUM);
        record.setAmount(new BigDecimal("10.00"));
        record.setStatus(OrderStatus.PENDING);
        return record;
    }

    private MissionOrder orderOf(BigDecimal amount) {
        MissionOrder order = new MissionOrder();
        order.setOrderNum(ORDER_NUM);
        order.setTotalAmount(amount);
        order.setOrderStatus(OrderStatus.PENDING);
        return order;
    }

    /** 构造被订单锁定的应征记录（selectedApplicationId 固定为 7L）。 */
    private TaskApplication quotedApplication(BigDecimal quotedAmount) {
        TaskApplication application = new TaskApplication();
        application.setQuotedAmount(quotedAmount);
        return application;
    }

    @Test
    @DisplayName("回调金额与订单一致 → 订单与支付记录标记 PAID")
    void matchingAmountMarksPaid() {
        PayRecord record = pendingRecord();
        MissionOrder order = orderOf(new BigDecimal("10.00"));
        order.setSelectedApplicationId(7L);
        when(payRecordRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(record));
        when(orderRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(order));
        when(taskApplicationRepository.findById(7L))
                .thenReturn(Optional.of(quotedApplication(new BigDecimal("10.00"))));

        service.handleNotify("tx-1", ORDER_NUM, "SUCCESS", 1000); // 10.00 元 = 1000 分

        assertThat(record.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(record.getTransactionId()).isEqualTo("tx-1");
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository).save(order);
        verify(payRecordAuditService, never()).writeRecordError(any(), any());
    }

    @Test
    @DisplayName("回调金额与订单不符 → 抛异常拒绝入账，记录不标记 PAID，错误信息独立事务留痕")
    void mismatchedAmountRejected() {
        PayRecord record = pendingRecord();
        MissionOrder order = orderOf(new BigDecimal("10.00"));
        when(payRecordRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(record));
        when(orderRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(order));

        // 篡改：实付 1 分 vs 订单 1000 分
        assertThatThrownBy(() -> service.handleNotify("tx-2", ORDER_NUM, "SUCCESS", 1))
                .isInstanceOf(PayNotifyException.class)
                .hasMessageContaining("金额");

        assertThat(record.getStatus()).isNotEqualTo(OrderStatus.PAID);
        verify(payRecordAuditService).writeRecordError(eq(RECORD_ID), contains("金额不符"));
        verify(orderRepository, never()).save(any(MissionOrder.class));
    }

    @Test
    @DisplayName("订单金额与系统报价不一致 → 拒绝入账并留痕（ADR-0003 不允许改价）")
    void mismatchedQuoteRejected() {
        PayRecord record = pendingRecord();
        MissionOrder order = orderOf(new BigDecimal("10.00"));
        order.setSelectedApplicationId(7L);
        when(payRecordRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(record));
        when(orderRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(order));
        when(taskApplicationRepository.findById(7L))
                .thenReturn(Optional.of(quotedApplication(new BigDecimal("99.99"))));

        // 回调金额 1000 分与订单 10.00 元一致（通过回调金额比对），但订单金额 ≠ 系统报价 99.99
        assertThatThrownBy(() -> service.handleNotify("tx-5", ORDER_NUM, "SUCCESS", 1000))
                .isInstanceOf(PayNotifyException.class)
                .hasMessageContaining("报价");

        assertThat(record.getStatus()).isNotEqualTo(OrderStatus.PAID);
        assertThat(order.getOrderStatus()).isNotEqualTo(OrderStatus.PAID);
        verify(payRecordAuditService).writeRecordError(eq(RECORD_ID), contains("报价"));
        verify(orderRepository, never()).save(any(MissionOrder.class));
    }

    @Test
    @DisplayName("回调缺少金额字段 → 同样拒绝入账并留痕")
    void missingAmountRejected() {
        PayRecord record = pendingRecord();
        MissionOrder order = orderOf(new BigDecimal("10.00"));
        when(payRecordRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(record));
        when(orderRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.handleNotify("tx-3", ORDER_NUM, "SUCCESS", null))
                .isInstanceOf(PayNotifyException.class);

        assertThat(record.getStatus()).isNotEqualTo(OrderStatus.PAID);
        verify(payRecordAuditService).writeRecordError(eq(RECORD_ID), contains("金额不符"));
    }

    @Test
    @DisplayName("回调对应订单不存在 → 拒绝入账并留痕")
    void missingOrderRejected() {
        PayRecord record = pendingRecord();
        when(payRecordRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(record));
        when(orderRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleNotify("tx-4", ORDER_NUM, "SUCCESS", 1000))
                .isInstanceOf(PayNotifyException.class)
                .hasMessageContaining("不存在");

        verify(payRecordAuditService).writeRecordError(eq(RECORD_ID), contains("订单不存在"));
        assertThat(record.getStatus()).isNotEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("非 SUCCESS 状态 → 记录标记 CANCELLED（原行为保留）")
    void failedCallbackMarksCancelled() {
        PayRecord record = pendingRecord();
        when(payRecordRepository.findByOrderNum(ORDER_NUM)).thenReturn(Optional.of(record));

        service.handleNotify(null, ORDER_NUM, "FAILED", null);

        assertThat(record.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository, never()).save(any(MissionOrder.class));
    }
}

package com.uav.pay.service.impl;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.pay.MockPayGuard;
import com.uav.pay.config.WeChatPayConfig;
import com.uav.pay.mapper.PayRecordRepository;
import com.uav.pay.pojo.entity.PayRecord;
import com.uav.pay.pojo.vo.PayResultVO;
import com.uav.pay.service.WeChatPayService;
import com.uav.pay.util.WeChatPayUtil;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.exception.PayNotifyException;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
public class WeChatPayServiceImpl implements WeChatPayService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PayRecordRepository payRecordRepository;

    @Autowired
    private TaskApplicationRepository taskApplicationRepository;

    @Autowired
    private WeChatPayConfig weChatPayConfig;

    @Autowired
    private MockPayGuard mockPayGuard;

    @Autowired
    private PayRecordAuditService payRecordAuditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultVO pay(String orderNum, Long userId, String openid) {
        MissionOrder order = orderRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND, "无权支付此订单");
        }
        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "当前订单状态不允许支付");
        }
        // ADR-0003 决定 2「不允许改价」：支付前硬校验 totalAmount == 选定应征的 quotedAmount
        requireLockedQuote(order);

        PayRecord existing = payRecordRepository.findByOrderNum(orderNum).orElse(null);
        if (existing != null && existing.getPrepayId() != null
                && existing.getStatus() == OrderStatus.PENDING) {
            return new PayResultVO(orderNum, existing.getPrepayId(), null);
        }

        PayRecord record = new PayRecord();
        record.setOrderNum(orderNum);
        record.setUserId(userId);
        record.setAmount(order.getTotalAmount());
        record.setPayChannel("WECHAT");
        record.setStatus(OrderStatus.PENDING);
        payRecordRepository.save(record);

        // 1B-1a：mock 支付通道（仅 dev/test profile + wechat.pay.mock-enabled=true 双重门禁）。
        // 复用真实 handleNotify 状态机（PENDING→PAID，含 t3 金额比对）：mock 通道按订单应付金额全额支付，
        // 并生成 mock 交易流水与 prepayId；openid 由服务端生成，不依赖真实微信身份。
        if (mockPayGuard.isMockPayActive()) {
            String mockOpenid = (openid == null || openid.isBlank())
                    ? "mock-openid-" + userId : openid;
            String mockTxId = "mock-" + orderNum + "-" + UUID.randomUUID().toString().substring(0, 8);
            handleNotify(mockTxId, orderNum, "SUCCESS", toCents(order.getTotalAmount()));
            record.setPrepayId("mock-prepay-" + orderNum);
            payRecordRepository.save(record);
            log.info("[MOCK PAY] 订单 {} mock 支付完成, mockTxId={}, mockOpenid={}", orderNum, mockTxId, mockOpenid);
            return new PayResultVO(orderNum, record.getPrepayId(), null);
        }

        // 1B-1a：真实链路必须有 openid（微信 JSAPI 支付人身份）
        if (openid == null || openid.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "openid 不能为空");
        }

        String description = "无人机任务-" + orderNum;
        try {
            var result = WeChatPayUtil.createJsapiOrder(
                    weChatPayConfig, orderNum, openid, description, order.getTotalAmount());
            record.setPrepayId(result.get("prepayId"));
            payRecordRepository.save(record);
            return new PayResultVO(orderNum, result.get("prepayId"), null);
        } catch (Exception e) {
            record.setStatus(OrderStatus.CANCELLED);
            record.setErrorMsg("微信下单失败: " + e.getMessage());
            payRecordRepository.save(record);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "支付发起失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleNotify(String transactionId, String orderNum, String status, Integer callbackAmountCents) {
        if (transactionId != null) {
            PayRecord existingByTransId = payRecordRepository.findByTransactionId(transactionId).orElse(null);
            if (existingByTransId != null) {
                log.info("重复回调，微信流水号 {} 已处理，跳过", transactionId);
                return;
            }
        }

        PayRecord record = payRecordRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "支付记录不存在"));

        if (!"SUCCESS".equals(status)) {
            record.setStatus(OrderStatus.CANCELLED);
            record.setErrorMsg("支付失败, 微信状态: " + status);
            payRecordRepository.save(record);
            log.warn("支付回调失败, 订单号: {}, 微信状态: {}", orderNum, status);
            return;
        }

        MissionOrder order = orderRepository.findByOrderNum(orderNum).orElse(null);
        if (order == null) {
            log.error("支付回调对应订单不存在，拒绝入账: {}", orderNum);
            payRecordAuditService.writeRecordError(record.getId(), "支付回调对应订单不存在");
            throw new PayNotifyException("支付回调对应订单不存在: " + orderNum);
        }

        // P0-3：回调金额必须与订单应支付金额一致，否则拒绝入账并留下审计记录
        Integer expectedCents = toCents(order.getTotalAmount());
        if (callbackAmountCents == null || !callbackAmountCents.equals(expectedCents)) {
            log.error("[资损防护] 支付回调金额不符，拒绝入账。orderNum={}, 期望={}分, 回调={}分",
                    orderNum, expectedCents, callbackAmountCents);
            // 留痕走独立事务：主事务随 PayNotifyException 回滚，审计信息必须存活
            payRecordAuditService.writeRecordError(record.getId(),
                    "支付回调金额不符: 订单 " + expectedCents + " 分, 回调 " + callbackAmountCents + " 分");
            throw new PayNotifyException("支付回调金额与订单金额不一致");
        }

        // ADR-0003「不允许改价」：回调入账前同样硬校验 totalAmount == 选定应征 quotedAmount
        // （防订单金额在支付窗口被篡改；不一致拒绝入账并审计留痕）
        try {
            requireLockedQuote(order);
        } catch (BusinessException e) {
            log.error("[资损防护] 支付回调触发报价一致性校验失败，拒绝入账。orderNum={}, err={}",
                    orderNum, e.getMessage());
            payRecordAuditService.writeRecordError(record.getId(), e.getMessage());
            throw new PayNotifyException(e.getMessage());
        }

        record.setTransactionId(transactionId);
        record.setStatus(OrderStatus.PAID);
        record.setPayTime(LocalDateTime.now());
        payRecordRepository.save(record);

        order.setOrderStatus(OrderStatus.PAID);
        orderRepository.save(order);

        // ADR-0003 决定 3：支付成功 → 待飞手确认（撮合状态机推进 + 拦截器向飞手发通知）
        Task task = order.getTask();
        if (task != null) {
            if (task.getMatchStatus() == MatchStatus.AWAITING_PAYMENT) {
                task.setMatchStatus(MatchStatus.AWAITING_RIDER_CONFIRM);
            } else if (task.getMatchStatus() != MatchStatus.AWAITING_RIDER_CONFIRM) {
                // 订单已支付但撮合状态不符：金额安全优先（不回滚入账），留错误日志供排查
                log.error("[撮合状态异常] 支付成功但任务 {} 撮合状态为 {}（期望 AWAITING_PAYMENT）",
                        task.getTaskNum(), task.getMatchStatus());
            }
        }

        log.info("支付回调成功, 订单号: {}, 微信流水号: {}, 金额: {}分", orderNum, transactionId, callbackAmountCents);
    }

    /**
     * 支付硬校验（ADR-0003 决定 2「不允许改价」）：订单必须经过「用户选定应征」锁定金额，
     * 且 {@code totalAmount} 严格等于该应征的 {@code quotedAmount}，否则
     * {@link ApiErrorCode#AMOUNT_MISMATCH} 拒绝支付。
     */
    private void requireLockedQuote(MissionOrder order) {
        if (order.getSelectedApplicationId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AMOUNT_MISMATCH,
                    "订单未锁定系统报价（未经过选定应征流程），拒绝支付");
        }
        TaskApplication application = taskApplicationRepository.findById(order.getSelectedApplicationId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AMOUNT_MISMATCH,
                        "订单锁定的应征记录不存在，拒绝支付"));
        if (order.getTotalAmount() == null
                || order.getTotalAmount().compareTo(application.getQuotedAmount()) != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AMOUNT_MISMATCH,
                    "订单金额与系统报价不一致（成交价必须等于 quotedAmount），拒绝支付");
        }
    }

    private Integer toCents(BigDecimal amountYuan) {
        return amountYuan.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refund(String orderNum, String reason) {
        PayRecord payRecord = payRecordRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        ApiErrorCode.PAY_RECORD_NOT_FOUND));
        if (payRecord.getTransactionId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.REFUND_FAILED,
                    "该支付记录无微信流水号，无法退款");
        }

        // 生成退款单号
        String outRefundNo = "RF" + orderNum.substring(2)
                + String.valueOf(System.currentTimeMillis()).substring(7);

        try {
            WeChatPayUtil.refund(weChatPayConfig,
                    payRecord.getTransactionId(),
                    outRefundNo,
                    reason,
                    payRecord.getAmount(),
                    payRecord.getAmount()); // 全额退款
        } catch (Exception e) {
            log.error("微信退款失败, 订单号: {}, err: {}", orderNum, e.getMessage());
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.REFUND_FAILED,
                    "微信退款失败: " + e.getMessage());
        }

        // 更新支付记录
        payRecord.setStatus(OrderStatus.REFUNDED);
        payRecord.setUpdateTime(LocalDateTime.now());
        payRecordRepository.save(payRecord);

        // 更新订单状态
        orderRepository.findByOrderNum(orderNum).ifPresent(order -> {
            order.setOrderStatus(OrderStatus.REFUNDED);
            orderRepository.save(order);
        });

        log.info("退款成功, 订单号: {}, 退款单号: {}, 金额: {}元",
                orderNum, outRefundNo, payRecord.getAmount());
    }
}

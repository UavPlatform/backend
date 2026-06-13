package com.uav.pay.service.impl;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.pay.config.WeChatPayConfig;
import com.uav.pay.mapper.PayRecordRepository;
import com.uav.pay.pojo.entity.PayRecord;
import com.uav.pay.pojo.vo.PayResultVO;
import com.uav.pay.service.WeChatPayService;
import com.uav.pay.util.WeChatPayUtil;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
public class WeChatPayServiceImpl implements WeChatPayService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PayRecordRepository payRecordRepository;

    @Autowired
    private WeChatPayConfig weChatPayConfig;

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
    public void handleNotify(String transactionId, String orderNum, String status) {
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

        record.setTransactionId(transactionId);
        record.setStatus(OrderStatus.PAID);
        record.setPayTime(LocalDateTime.now());
        payRecordRepository.save(record);

        orderRepository.findByOrderNum(orderNum).ifPresent(order -> {
            order.setOrderStatus(OrderStatus.PAID);
            orderRepository.save(order);
        });

        log.info("支付回调成功, 订单号: {}, 微信流水号: {}", orderNum, transactionId);
    }
}

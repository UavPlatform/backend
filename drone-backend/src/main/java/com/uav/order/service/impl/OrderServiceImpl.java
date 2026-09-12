package com.uav.order.service.impl;

import com.uav.order.mapper.OrderRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskWaypoint;
import com.uav.server.calculator.RoutePriceCalculator;
import com.uav.server.config.OrderConfig;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.OrderIdGenerator;
import com.uav.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderConfig orderConfig;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MissionOrder createOrder(Long userId, String taskNum) {
        Optional<Task> taskOpt = taskRepository.findByTaskNum(taskNum);
        if (taskOpt.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ROUTE_NOT_FOUND);
        }

        Task task = taskOpt.get();
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权使用此任务");
        }

        Optional<MissionOrder> unpaid = orderRepository.findByUserIdAndOrderStatusForUpdate(userId, OrderStatus.PENDING);
        if (unpaid.isPresent()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_ALREADY_EXISTS);
        }

        // P0-2：金额一律由服务端按航点距离计算，客户端传入的 reward 不作为金额依据
        List<TaskWaypoint> waypoints = task.getWaypoints();
        BigDecimal distance = RoutePriceCalculator.calculateTotalDistance(waypoints);
        BigDecimal pricePerMeter = orderConfig.getPricePerMeter();
        if (pricePerMeter == null || pricePerMeter.signum() <= 0) {
            log.error("订单计价配置非法（order.price-per-meter={}），拒绝创建订单", pricePerMeter);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "订单计价配置缺失，已拒绝创建订单");
        }
        BigDecimal totalAmount = RoutePriceCalculator.calculatePrice(distance, pricePerMeter);
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "订单金额计算为 0（航点不足或距离过近），已拒绝创建订单");
        }

        String orderNum = OrderIdGenerator.generate(userId);

        MissionOrder order = new MissionOrder();
        order.setOrderNum(orderNum);
        order.setUserId(userId);
        order.setTask(task);
        order.setTotalAmount(totalAmount);
        order.setTotalDistance(distance);
        order.setOrderStatus(OrderStatus.PENDING);

        try {
            orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            log.warn("并发冲突，用户ID {} 已有待支付订单，唯一约束拦截", userId);
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_ALREADY_EXISTS);
        }

        log.info("订单创建成功，订单号: {}, 用户ID: {}, 金额: {}元, 距离: {}m",
                orderNum, userId, order.getTotalAmount(), distance);

        if (order.getTask() != null) {
            order.getTask().getTaskName();
        }

        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MissionOrder> listOrders(Long userId, int page, int size) {
        return orderRepository.findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));
    }

    @Override
    @Transactional(readOnly = true)
    public MissionOrder getOrderDetail(String orderNum, Long userId) {
        MissionOrder order = orderRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }

        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(String orderNum, Long userId) {
        MissionOrder order = orderRepository.findByOrderNumForUpdate(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "仅待支付状态的订单可以取消，当前状态: " + order.getOrderStatus().getDesc());
        }

        order.setOrderStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        log.info("订单取消成功，订单号: {}, 用户ID: {}", orderNum, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateExecuteResult(String orderNum, String resultUuid) {
        MissionOrder order = orderRepository.findByOrderNumForUpdate(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        order.setExecuteResult(resultUuid);
        orderRepository.save(order);
        log.info("订单 executeResult 更新，订单号: {}, uuid: {}", orderNum, resultUuid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disputeOrder(String orderNum, Long userId) {
        MissionOrder order = orderRepository.findByOrderNumForUpdate(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }

        if (order.getOrderStatus() != OrderStatus.WAITING_CONFIRM) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "仅待确认状态的订单可发起争议，当前状态: " + order.getOrderStatus().getDesc());
        }

        order.setOrderStatus(OrderStatus.DISPUTED);
        orderRepository.save(order);
        log.info("订单已标记为争议中，订单号: {}, 用户ID: {}", orderNum, userId);
    }
}

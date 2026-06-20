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
    public MissionOrder createOrder(Long userId, String taskNum, Double reward) {
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
        // 自动计算价格逻辑注释保留，价格改为前端传 reward
        List<TaskWaypoint> waypoints = task.getWaypoints();
        BigDecimal distance = RoutePriceCalculator.calculateTotalDistance(waypoints);
        // BigDecimal totalAmount = RoutePriceCalculator.calculatePrice(distance, orderConfig.getPricePerMeter());

        String orderNum = OrderIdGenerator.generate(userId);

        MissionOrder order = new MissionOrder();
        order.setOrderNum(orderNum);
        order.setUserId(userId);
        order.setTask(task);
        order.setTotalAmount(reward != null ? BigDecimal.valueOf(reward) : BigDecimal.ZERO);
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
}

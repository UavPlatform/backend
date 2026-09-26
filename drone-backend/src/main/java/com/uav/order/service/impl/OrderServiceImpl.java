package com.uav.order.service.impl;

import com.uav.order.mapper.OrderRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskWaypoint;
import com.uav.server.calculator.RoutePriceCalculator;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.MatchStatus;
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

        // TASK-BACKEND-004 / ADR-0003 决定 3：发单创建「待撮合」草稿订单（MATCHING），不强制支付。
        // 金额未锁定（totalAmount=0，选定应征时由 selectRider 写死 = quotedAmount）；
        // MATCHING 不占用 pending_key 单例约束，同一用户可并行发布多个需求（冲突点 1 解除）。
        List<TaskWaypoint> waypoints = task.getWaypoints();
        BigDecimal distance = RoutePriceCalculator.calculateTotalDistance(waypoints);

        String orderNum = OrderIdGenerator.generate(userId);

        MissionOrder order = new MissionOrder();
        order.setOrderNum(orderNum);
        order.setUserId(userId);
        order.setTask(task);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setTotalDistance(distance);
        order.setOrderStatus(OrderStatus.MATCHING);

        try {
            orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            log.warn("并发冲突，用户ID {} 订单唯一约束拦截", userId);
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_ALREADY_EXISTS);
        }

        log.info("待撮合订单创建成功，订单号: {}, 用户ID: {}, 距离: {}m（金额待选定应征时锁定）",
                orderNum, userId, distance);

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

        // TASK-BACKEND-004：取消支付后重新开放撮合（AWAITING_PAYMENT → NEGOTIATING），
        // 用户可重新选定应征并再次下单（select-rider 允许 CANCELLED 订单重新激活为 PENDING）。
        Task task = order.getTask();
        if (task != null && task.getMatchStatus() == MatchStatus.AWAITING_PAYMENT) {
            task.setMatchStatus(MatchStatus.NEGOTIATING);
            taskRepository.save(task);
        }
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

package com.drone.service.impl;

import com.drone.mapper.OrderRepository;
import com.drone.mapper.RouteRepository;
import com.drone.pojo.entity.MissionOrder;
import com.drone.pojo.enums.OrderStatus;
import com.drone.pojo.entity.Route;
import com.drone.pojo.entity.RouteWaypoint;
import com.drone.server.calculator.RoutePriceCalculator;
import com.drone.server.config.OrderConfig;
import com.drone.pojo.enums.ApiErrorCode;
import com.drone.server.exception.BusinessException;
import com.drone.server.util.LogMaskUtil;
import com.drone.server.util.OrderIdGenerator;
import com.drone.service.OrderService;
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
    private RouteRepository routeRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderConfig orderConfig;

    @Override
    @Transactional
    public MissionOrder createOrder(String name, String routeNum) {
        // 先校验航线
        Optional<Route> routeOpt = routeRepository.findByRouteNum(routeNum);
        if (routeOpt.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ROUTE_NOT_FOUND);
        }

        Route route = routeOpt.get();
        if (!route.getUserName().equals(name)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权使用此航线");
        }

        // 航线校验通过后再拿锁检查已有未支付订单
        Optional<MissionOrder> unpaid = orderRepository.findByUserNameAndOrderStatusForUpdate(name, OrderStatus.PENDING);
        if (unpaid.isPresent()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_ALREADY_EXISTS);
        }
        List<RouteWaypoint> waypoints = route.getWaypoints();

        BigDecimal distance = RoutePriceCalculator.calculateTotalDistance(waypoints);
        BigDecimal totalAmount = RoutePriceCalculator.calculatePrice(distance, orderConfig.getPricePerMeter());

        String orderNum = OrderIdGenerator.generate(name);

        MissionOrder order = new MissionOrder();
        order.setOrderNum(orderNum);
        order.setUserName(name);
        order.setRoute(route);
        order.setDjiId(route.getDjiId());
        order.setTotalAmount(totalAmount);
        order.setTotalDistance(distance);
        order.setOrderStatus(OrderStatus.PENDING);

        try {
            orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            log.warn("并发冲突，用户 {} 已有待支付订单，唯一约束拦截", LogMaskUtil.maskUserName(name));
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_ALREADY_EXISTS);
        }

        log.info("订单创建成功，订单号: {}, 用户: {}, 金额: {}元, 距离: {}m",
                orderNum, LogMaskUtil.maskUserName(name), totalAmount, distance);

        // 事务内触碰懒加载字段，确保返回给 Controller 后 open-in-view=false 时可用
        if (order.getRoute() != null) {
            order.getRoute().getRouteName();
        }

        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MissionOrder> listOrders(String name, int page, int size) {
        return orderRepository.findByUserNameOrderByCreateTimeDesc(name, PageRequest.of(page, size));
    }

    @Override
    @Transactional(readOnly = true)
    public MissionOrder getOrderDetail(String orderNum, String name) {
        MissionOrder order = orderRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));

        if (!order.getUserName().equals(name)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }

        return order;
    }

    @Override
    @Transactional
    public void cancelOrder(String orderNum, String name) {
        MissionOrder order = orderRepository.findByOrderNumForUpdate(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));

        if (!order.getUserName().equals(name)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "仅待支付状态的订单可以取消，当前状态: " + order.getOrderStatus().getDesc());
        }

        order.setOrderStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        log.info("订单取消成功，订单号: {}, 用户: {}", orderNum, LogMaskUtil.maskUserName(name));
    }
}

package com.uav.order.service.impl;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.vo.TradeStatsVO;
import com.uav.order.service.OrderTradeService;
import com.uav.server.enums.OrderStatus;
import com.uav.task.mapper.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderTradeServiceImpl implements OrderTradeService {

    private final OrderRepository orderRepository;
    private final TaskRepository taskRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<MissionOrder> listMyTrades(Long userId, int page, int size, String status) {
        OrderStatus orderStatus = OrderStatus.fromNameOrCode(status);
        PageRequest pageable = PageRequest.of(page, size);
        if (orderStatus == null) {
            return orderRepository.findMyTrades(userId, pageable);
        }
        return orderRepository.findMyTradesByStatus(userId, orderStatus, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MissionOrder> listSoldOrders(Long userId, int page, int size, String status) {
        OrderStatus orderStatus = OrderStatus.fromNameOrCode(status);
        PageRequest pageable = PageRequest.of(page, size);
        if (orderStatus == null) {
            return orderRepository.findSoldOrders(userId, pageable);
        }
        return orderRepository.findSoldOrdersByStatus(userId, orderStatus, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MissionOrder> listPendingReviewOrders(Long userId, int page, int size) {
        return orderRepository.findPendingReviewOrders(userId, OrderStatus.COMPLETED,
                PageRequest.of(page, size));
    }

    @Override
    @Transactional(readOnly = true)
    public TradeStatsVO getTradeStats(Long userId) {
        TradeStatsVO vo = new TradeStatsVO();
        vo.setTotalOrderCount(orderRepository.countMyTrades(userId));
        vo.setCompletedTradeCount(orderRepository.countMyTradesByStatus(userId, OrderStatus.COMPLETED));
        vo.setPublishedTaskCount(taskRepository.countByUserId(userId));
        vo.setBoughtOrderCount(orderRepository.countByUserId(userId));
        vo.setSoldOrderCount(orderRepository.countSoldOrders(userId));
        // 待评价仅统计「已完成」——评价接口对非已完成订单会拒绝，口径不一致会出现点了就报错的入口
        vo.setPendingReviewCount(orderRepository.countPendingReviewOrders(userId, OrderStatus.COMPLETED));
        return vo;
    }
}

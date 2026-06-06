package com.uav.order.service;

import com.uav.order.pojo.entity.MissionOrder;
import org.springframework.data.domain.Page;

public interface OrderService {

    MissionOrder createOrder(Long userId, String taskNum);

    Page<MissionOrder> listOrders(Long userId, int page, int size);

    MissionOrder getOrderDetail(String orderNum, Long userId);

    void cancelOrder(String orderNum, Long userId);
}

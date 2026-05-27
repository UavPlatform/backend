package com.drone.service;

import com.drone.pojo.entity.MissionOrder;
import org.springframework.data.domain.Page;

public interface OrderService {

    MissionOrder createOrder(String name, String routeNum);

    Page<MissionOrder> listOrders(String name, int page, int size);

    MissionOrder getOrderDetail(String orderNum, String name);

    void cancelOrder(String orderNum, String name);
}

package com.uav.order.service;

import com.uav.order.pojo.entity.MissionOrder;
import org.springframework.data.domain.Page;

public interface OrderService {

    MissionOrder createOrder(Long userId, String taskNum);

    Page<MissionOrder> listOrders(Long userId, int page, int size);

    MissionOrder getOrderDetail(String orderNum, Long userId);

    void cancelOrder(String orderNum, Long userId);

    /**
     * 更新订单的 executeResult（文件上传后绑定）
     * @param orderNum 订单号
     * @param resultUuid 32 字符 UUID（去横杠），作为文件目录标识
     */
    void updateExecuteResult(String orderNum, String resultUuid);
}

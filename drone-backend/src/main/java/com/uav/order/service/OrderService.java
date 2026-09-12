package com.uav.order.service;

import com.uav.order.pojo.entity.MissionOrder;
import org.springframework.data.domain.Page;

public interface OrderService {

    /**
     * 按任务航点由服务端计价创建 PENDING 订单（P0-2：金额一律服务端计算，
     * 客户端传入的 reward 不再作为金额依据）。
     */
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

    /**
     * 用户对交付结果不满意，将订单置为争议中
     * @param orderNum 订单号
     * @param userId   用户 ID
     */
    void disputeOrder(String orderNum, Long userId);
}

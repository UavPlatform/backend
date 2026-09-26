package com.uav.order.service;

import com.uav.order.pojo.entity.MissionOrder;
import org.springframework.data.domain.Page;

public interface OrderService {

    /**
     * 发单时创建「待撮合」草稿订单（ADR-0003 决定 3）：状态 MATCHING，totalAmount=0（未锁定），
     * 不强制支付、不占用 pending_key 单例约束。金额锁定发生在用户选定应征（selectRider），
     * 锁定值 = 该应征的 {@code quotedAmount}（缺省为系统基准价，飞手可在区间内报价），
     * 支付前由 pay/handleNotify 硬校验 totalAmount == quotedAmount。
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

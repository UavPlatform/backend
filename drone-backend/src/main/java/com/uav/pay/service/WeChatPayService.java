package com.uav.pay.service;

import com.uav.pay.pojo.vo.PayResultVO;

public interface WeChatPayService {
    PayResultVO pay(String orderNum, Long userId, String openid);

    void handleNotify(String transactionId, String orderNum, String status);

    /**
     * 退款 — 根据订单号查找支付记录，发起微信退款
     * @param orderNum 订单号
     * @param reason   退款原因
     */
    void refund(String orderNum, String reason);
}

package com.uav.pay.service;

import com.uav.pay.pojo.vo.PayResultVO;

public interface WeChatPayService {
    PayResultVO pay(String orderNum, Long userId, String openid);

    /**
     * 处理支付回调。
     * @param transactionId 微信支付流水号
     * @param orderNum 商户订单号
     * @param status 微信交易状态（SUCCESS / FAILED）
     * @param callbackAmountCents 微信回调的实付金额（单位：分），金额比对失败将拒绝入账
     */
    void handleNotify(String transactionId, String orderNum, String status, Integer callbackAmountCents);

    /**
     * 退款 — 根据订单号查找支付记录，发起微信退款
     * @param orderNum 订单号
     * @param reason   退款原因
     */
    void refund(String orderNum, String reason);
}

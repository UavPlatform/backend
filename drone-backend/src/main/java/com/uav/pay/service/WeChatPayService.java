package com.uav.pay.service;

import com.uav.pay.pojo.vo.PayResultVO;

public interface WeChatPayService {
    PayResultVO pay(String orderNum, Long userId, String openid);

    void handleNotify(String transactionId, String orderNum, String status);
}

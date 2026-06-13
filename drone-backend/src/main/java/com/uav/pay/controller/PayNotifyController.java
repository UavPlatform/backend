package com.uav.pay.controller;

import com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result;
import com.uav.pay.config.WeChatPayConfig;
import com.uav.pay.service.WeChatPayService;
import com.uav.pay.util.WeChatPayUtil;
import com.uav.server.annotation.SkipJwt;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/pay")
public class PayNotifyController {

    @Autowired
    private WeChatPayService weChatPayService;

    @Autowired
    private WeChatPayConfig weChatPayConfig;

    @SkipJwt
    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> handleNotify(HttpServletRequest request) {
        WxPayNotifyV3Result result = WeChatPayUtil.parseNotifyResult(request, weChatPayConfig);
        WxPayNotifyV3Result.DecryptNotifyResult data = result.getResult();

        String tradeState = data.getTradeState();
        log.info("支付回调验签通过, 订单号: {}, 状态: {}", data.getOutTradeNo(), tradeState);

        weChatPayService.handleNotify(
                data.getTransactionId(),
                data.getOutTradeNo(),
                "SUCCESS".equals(tradeState) ? "SUCCESS" : "FAILED");

        return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "OK"));
    }
}

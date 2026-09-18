package com.uav.pay.controller;

import com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result;
import com.uav.pay.config.WeChatPayConfig;
import com.uav.pay.service.WeChatPayService;
import com.uav.pay.util.WeChatPayUtil;
import com.uav.server.annotation.SkipJwt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Pay Notify API", description = "微信支付结果回调（服务端到服务端，非消费端接口）")
@Slf4j
@RestController
@RequestMapping("/pay")
public class PayNotifyController {

    @Autowired
    private WeChatPayService weChatPayService;

    @Autowired
    private WeChatPayConfig weChatPayConfig;

    @Operation(summary = "微信支付结果回调",
            description = "微信支付平台异步通知入口：验签后按 out_trade_no 与实付金额核对并推进订单状态；"
                    + "应答 code=SUCCESS 表示已受理，其余情况微信会重试。")
    @SkipJwt
    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> handleNotify(HttpServletRequest request) {
        WxPayNotifyV3Result result = WeChatPayUtil.parseNotifyResult(request, weChatPayConfig);
        WxPayNotifyV3Result.DecryptNotifyResult data = result.getResult();

        String tradeState = data.getTradeState();
        Integer callbackAmountCents = data.getAmount() != null ? data.getAmount().getTotal() : null;
        log.info("支付回调验签通过, 订单号: {}, 状态: {}, 金额: {}分", data.getOutTradeNo(), tradeState, callbackAmountCents);

        weChatPayService.handleNotify(
                data.getTransactionId(),
                data.getOutTradeNo(),
                "SUCCESS".equals(tradeState) ? "SUCCESS" : "FAILED",
                callbackAmountCents);

        return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "OK"));
    }
}

package com.uav.pay.controller;

import com.uav.pay.pojo.vo.PayResultVO;
import com.uav.pay.service.WeChatPayService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Pay API", description = "支付接口")
@RestController
@RequestMapping("/pay")
public class PayController {

    @Autowired
    private WeChatPayService weChatPayService;

    @OperationLog("发起支付")
    @Operation(summary = "发起微信支付", description = "调用微信统一下单，返回 prepay_id 供前端唤起收银台",
            parameters = {
                    @Parameter(name = "orderNum", description = "订单号", required = true),
                    @Parameter(name = "openid", description = "微信 openid", required = true)
            })
    @PostMapping("/{orderNum}")
    public Result<PayResultVO> pay(@PathVariable String orderNum, @RequestParam String openid) {
        Long userId = UserContext.getUserId();
        PayResultVO result = weChatPayService.pay(orderNum, userId, openid);
        return Result.success(result);
    }
}

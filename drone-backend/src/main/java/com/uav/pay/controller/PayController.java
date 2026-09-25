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
    @Operation(summary = "发起支付", description = "真实链路：调用微信统一下单返回 prepay_id（openid 必填）；"
                    + "mock 链路（仅 dev/test 且 wechat.pay.mock-enabled=true）：openid 可省略，服务端生成 mock 身份并"
                    + "同步完成支付状态流转（PENDING→PAID）",
            parameters = {
                    @Parameter(name = "orderNum", description = "订单号", required = true),
                    @Parameter(name = "openid", description = "微信 openid（真实链路必填；mock 链路可省略）", required = false)
            })
    @PostMapping("/{orderNum}")
    public Result<PayResultVO> pay(@PathVariable String orderNum,
                                   @RequestParam(required = false) String openid) {
        Long userId = UserContext.getUserId();
        PayResultVO result = weChatPayService.pay(orderNum, userId, openid);
        return Result.success(result);
    }
}

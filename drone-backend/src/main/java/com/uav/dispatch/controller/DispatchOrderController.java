package com.uav.dispatch.controller;

import com.uav.dispatch.pojo.dto.DispatchOrderDTO;
import com.uav.dispatch.pojo.dto.WaybillDraftDTO;
import com.uav.dispatch.pojo.vo.DispatchOrderVO;
import com.uav.dispatch.pojo.vo.DispatchReadinessVO;
import com.uav.dispatch.pojo.vo.WaybillAssessmentVO;
import com.uav.dispatch.service.DispatchOrderService;
import com.uav.dispatch.service.DispatchReadinessService;
import com.uav.dispatch.service.WaybillAssessmentService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "Dispatch Order API", description = "REQ-SHIP-PUB-003；当前仅支持开发测试环境模拟支付，空域数据不可用时禁止下单")
@RestController
@RequestMapping("/dispatch")
@RequireRole(0)
public class DispatchOrderController {
    private final DispatchReadinessService readiness;
    private final WaybillAssessmentService assessments;
    private final DispatchOrderService orders;
    public DispatchOrderController(DispatchReadinessService readiness, WaybillAssessmentService assessments,
                                   DispatchOrderService orders) {
        this.readiness = readiness;
        this.assessments = assessments;
        this.orders = orders;
    }
    @GetMapping("/readiness")
    @Operation(summary = "下单开放条件", description = "返回真实阻塞原因；不查询天气、不拼造总价；SIMULATED表示无真实扣款")
    public Result<DispatchReadinessVO> readiness() { return Result.success(readiness.current()); }

    @PostMapping("/assessments")
    @Operation(summary = "评估完整运单", description = "服务端校验全量文本并读取空域航程地形；缺失服务503，禁飞orderAllowed=false；评估15分钟有效，不是派单超时")
    public Result<WaybillAssessmentVO> assess(@Valid @RequestBody WaybillDraftDTO input) {
        return Result.success(assessments.assess(UserContext.getUserId(), input));
    }
    @PostMapping("/orders")
    @Operation(summary = "创建待付定金运单", description = "仅模拟环境；需真实性声明及本人有效评估，运单变更使旧评估失效；requestKey按用户幂等，不同内容重用返回409；金额为后端快照")
    public Result<DispatchOrderVO> create(@Valid @RequestBody DispatchOrderDTO input) {
        return Result.success(orders.create(UserContext.getUserId(), input));
    }
    @GetMapping("/orders")
    @Operation(summary = "我的派单运单", description = "返回本人最近50笔，独立于历史撮合订单；退出App后可继续查询待付款和退款结果")
    public Result<List<DispatchOrderVO>> listDispatchOrders() { return Result.success(orders.list(UserContext.getUserId())); }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "查询派单运单", description = "本人订单与独立资金状态，服务端结果为准")
    public Result<DispatchOrderVO> get(@PathVariable String orderId) {
        return Result.success(orders.get(UserContext.getUserId(), orderId));
    }
    @PostMapping("/orders/{orderId}/simulate-payment")
    @Operation(summary = "模拟支付定金", description = "仅dev/test及显式模拟开关；不接受客户端金额和支付成功标记；原子入账后进入等待接单，重复调用返回同一结果；非真实微信支付")
    public Result<DispatchOrderVO> pay(@PathVariable String orderId) {
        return Result.success(orders.simulatePay(UserContext.getUserId(), orderId));
    }
    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "取消派单运单", description = "仅当前模拟通道；未付取消不退款，已付全额模拟退定金，重复请求不重复退款；stage与fundsStatus独立，非真实到账")
    public Result<DispatchOrderVO> cancel(@PathVariable String orderId) {
        return Result.success(orders.cancel(UserContext.getUserId(), orderId));
    }
}

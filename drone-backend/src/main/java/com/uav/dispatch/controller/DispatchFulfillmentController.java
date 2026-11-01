package com.uav.dispatch.controller;

import com.uav.dispatch.pojo.dto.*;
import com.uav.dispatch.pojo.vo.*;
import com.uav.dispatch.service.DispatchFulfillmentService;
import com.uav.dispatch.service.DispatchMaterialService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/dispatch/orders") @RequireRole({0, 1})
@Tag(name = "Dispatch Fulfillment API", description = "真实运输状态及双方对象材料核验；只模拟资金，临时按真实起终点距离计价")
public class DispatchFulfillmentController {
    private final DispatchFulfillmentService flow;
    private final DispatchMaterialService materials;
    public DispatchFulfillmentController(DispatchFulfillmentService flow, DispatchMaterialService materials) { this.flow = flow; this.materials = materials; }
    @PostMapping("/{orderId}/start-transport") @RequireRole(1)
    @Operation(summary = "确认开始运输", description = "当前飞手、当前指派版本且已到场；记录业务运输状态，不发送飞控命令或伪称系统已核验实际起飞")
    public Result<DispatchOrderVO> startTransport(@PathVariable String orderId, @Valid @RequestBody DispatchAssignmentDTO input) {
        return Result.success(flow.start(UserContext.getUserId(), orderId, input.assignmentVersion()));
    }
    @PostMapping("/{orderId}/deliver") @RequireRole(1)
    @Operation(summary = "提交送达材料", description = "运输中飞手提交本人本次指派且用途为PILOT_DELIVERY的已核验对象材料，随后等待需求方收货材料；上传地址或PUT成功本身不算凭证")
    public Result<DispatchOrderVO> deliver(@PathVariable String orderId, @Valid @RequestBody DispatchDeliveryDTO input) {
        return Result.success(flow.deliver(UserContext.getUserId(), orderId, input.assignmentVersion(), input.materialIds(), input.note()));
    }
    @PostMapping("/{orderId}/receipt") @RequireRole(0)
    @Operation(summary = "提交收货材料并结单", description = "需求方提交本人SHIPPER_RECEIPT材料，重新核验双方对象存在及大小后结单；按下单距离快照结算；差额为负则全额模拟退差，不额外收定金")
    public Result<DispatchOrderVO> receipt(@PathVariable String orderId, @Valid @RequestBody DispatchReceiptDTO input) {
        return Result.success(flow.receipt(UserContext.getUserId(), orderId, input.materialIds()));
    }
    @PostMapping("/{orderId}/settle") @RequireRole(0)
    @Operation(summary = "模拟支付尾款", description = "仅订单本人、已结单及受限模拟环境；金额由后端下单距离快照减已付定金确定，不接收客户端金额，重复调用不重复入账")
    public Result<DispatchOrderVO> settle(@PathVariable String orderId) { return Result.success(flow.settle(UserContext.getUserId(), orderId)); }
    @PostMapping("/{orderId}/review") @RequireRole(0)
    @Operation(summary = "评价已结算订单", description = "本人已结单且资金结算完成后提交1至5分及可选文字，一单一次，相同请求幂等")
    public Result<DispatchOrderVO> review(@PathVariable String orderId, @Valid @RequestBody DispatchReviewDTO input) {
        return Result.success(flow.review(UserContext.getUserId(), orderId, input.score(), input.comment()));
    }
    @GetMapping("/{orderId}/materials")
    @Operation(summary = "读取已核验运单材料", description = "仅需求方或当前指派飞手；仅返回当前仍真实存在且大小匹配的已确认对象，不返回待上传记录")
    public Result<List<DispatchMaterialVO>> dispatchMaterials(@PathVariable String orderId) { return Result.success(materials.list(UserContext.getUserId(), orderId)); }
    @PostMapping("/{orderId}/materials/upload-ticket")
    @Operation(summary = "申请材料直传地址", description = "目的及阶段由后端校验；使用现有对象存储；客户端PUT必须不带业务Authorization，Content-Type与票据一致；此步骤不算材料成功")
    public Result<DispatchUploadTicketVO> ticket(@PathVariable String orderId, @Valid @RequestBody DispatchMaterialUploadDTO input) {
        return Result.success(materials.ticket(UserContext.getUserId(), orderId, input));
    }
    @PostMapping("/{orderId}/materials/{materialId}/confirm")
    @Operation(summary = "确认已上传对象材料", description = "仅票据上传人，本订单及当前阶段；服务端核验实际对象存在与字节数后才返回UPLOADED")
    public Result<DispatchMaterialVO> confirm(@PathVariable String orderId, @PathVariable String materialId) {
        return Result.success(materials.confirm(UserContext.getUserId(), orderId, materialId));
    }
}

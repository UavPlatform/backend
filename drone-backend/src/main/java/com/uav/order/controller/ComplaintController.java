package com.uav.order.controller;

import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.order.service.OrderComplaintService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.enums.ComplaintReason;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Complaint API", description = "订单投诉")
@RestController
@RequestMapping("/order")
@Slf4j
@RequiredArgsConstructor
public class ComplaintController {

    private final OrderComplaintService complaintService;

    @OperationLog("提交投诉")
    @RateLimiter(limit = 3, windowSeconds = 60)
    @Operation(summary = "提交投诉/退款申请",
            description = "对争议中的订单提交正式投诉，需提供原因和描述",
            parameters = {
                    @Parameter(name = "orderNum", description = "订单号", required = true),
                    @Parameter(name = "reason", description = "投诉原因: QUALITY_ISSUE/SERVICE_ISSUE/NOT_AS_DESCRIBED/OTHER", required = true),
                    @Parameter(name = "description", description = "详细描述")
            })
    @PostMapping("/complaint")
    public Result<Void> submitComplaint(@RequestParam String orderNum,
                                         @RequestParam ComplaintReason reason,
                                         @RequestParam(required = false) String description) {
        Long userId = UserContext.getUserId();
        complaintService.submitComplaint(orderNum, userId, reason, description);
        return Result.success("投诉已提交，等待处理");
    }

    @Operation(summary = "查看投诉状态",
            parameters = {@Parameter(name = "orderNum", description = "订单号", required = true)})
    @GetMapping("/complaint/{orderNum}")
    public Result<OrderComplaint> getComplaint(@PathVariable String orderNum) {
        Long userId = UserContext.getUserId();
        OrderComplaint complaint = complaintService.getComplaint(orderNum, userId);
        return Result.success(complaint);
    }

    @OperationLog("取消投诉")
    @RateLimiter(limit = 3, windowSeconds = 60)
    @Operation(summary = "取消投诉", description = "仅在管理员处理前可取消，取消后订单恢复为已完成")
    @PostMapping("/complaint/{id}/cancel")
    public Result<Void> cancelComplaint(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        complaintService.cancelComplaint(id, userId);
        return Result.success("投诉已取消");
    }
}

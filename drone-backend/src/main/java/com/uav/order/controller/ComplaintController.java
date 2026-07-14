package com.uav.order.controller;

import com.uav.order.mapper.OrderComplaintRepository;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.ComplaintReason;
import com.uav.server.enums.ComplaintStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Complaint API", description = "订单投诉")
@RestController
@RequestMapping("/order")
@Slf4j
@RequiredArgsConstructor
public class ComplaintController {

    private final OrderComplaintRepository complaintRepository;
    private final OrderRepository orderRepository;

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

        MissionOrder order = orderRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getOrderStatus() != OrderStatus.DISPUTED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "仅争议中的订单可提交投诉，当前状态: " + order.getOrderStatus().getDesc());
        }
        if (complaintRepository.existsByOrderNum(orderNum)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "该订单已有在处理的投诉");
        }

        OrderComplaint complaint = new OrderComplaint();
        complaint.setOrderNum(orderNum);
        complaint.setUserId(userId);
        complaint.setReason(reason);
        complaint.setDescription(description);
        complaint.setRefundAmount(order.getTotalAmount());
        complaintRepository.save(complaint);

        log.info("用户 {} 对订单 {} 提交投诉，原因: {}", userId, orderNum, reason.getDesc());
        return Result.success("投诉已提交，等待处理");
    }

    @Operation(summary = "查看投诉状态",
            parameters = {@Parameter(name = "orderNum", description = "订单号", required = true)})
    @GetMapping("/complaint/{orderNum}")
    public Result<OrderComplaint> getComplaint(@PathVariable String orderNum) {
        Long userId = UserContext.getUserId();
        OrderComplaint complaint = complaintRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "该订单无投诉记录"));
        if (!complaint.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }
        return Result.success(complaint);
    }

    @OperationLog("取消投诉")
    @RateLimiter(limit = 3, windowSeconds = 60)
    @Operation(summary = "取消投诉", description = "仅在管理员处理前可取消，取消后订单恢复为已完成")
    @PostMapping("/complaint/{id}/cancel")
    public Result<Void> cancelComplaint(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        OrderComplaint complaint = complaintRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "投诉不存在"));
        if (!complaint.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }
        if (complaint.getStatus() != ComplaintStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "仅待处理状态的投诉可取消");
        }

        // 投诉取消，订单恢复已完成
        orderRepository.findByOrderNum(complaint.getOrderNum()).ifPresent(order -> {
            order.setOrderStatus(OrderStatus.COMPLETED);
            orderRepository.save(order);
        });
        complaintRepository.delete(complaint);

        log.info("用户 {} 取消投诉，订单 {} 恢复已完成", userId, complaint.getOrderNum());
        return Result.success("投诉已取消");
    }
}

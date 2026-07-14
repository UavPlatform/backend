package com.uav.admin.controller;

import com.uav.order.mapper.OrderComplaintRepository;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.pay.service.WeChatPayService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RequireRole;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.ComplaintStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Admin Complaint API", description = "管理端投诉处理")
@RestController
@RequestMapping("/admin/complaint")
@Slf4j
@RequiredArgsConstructor
@RequireRole({1, 2})
public class AdminComplaintController {

    private final OrderComplaintRepository complaintRepository;
    private final OrderRepository orderRepository;
    private final WeChatPayService weChatPayService;

    @OperationLog("查看投诉列表")
    @Operation(summary = "投诉列表", description = "分页查看投诉，可按状态筛选")
    @GetMapping("/list")
    public Result<Map<String, Object>> listComplaints(@RequestParam(required = false) ComplaintStatus status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Page<OrderComplaint> complaintPage;
        if (status != null) {
            complaintPage = complaintRepository
                    .findByStatusOrderByCreateTimeAsc(status, PageRequest.of(page, size));
        } else {
            complaintPage = complaintRepository
                    .findAllByOrderByCreateTimeDesc(PageRequest.of(page, size));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complaints", complaintPage.getContent());
        result.put("currentPage", complaintPage.getNumber());
        result.put("totalPages", complaintPage.getTotalPages());
        result.put("totalElements", complaintPage.getTotalElements());
        return Result.success(result);
    }

    @OperationLog("批准投诉")
    @Operation(summary = "批准投诉并退款",
            description = "批准后自动发起微信退款，订单状态变更为已退款",
            parameters = {
                    @Parameter(name = "id", description = "投诉 ID", required = true),
                    @Parameter(name = "adminNote", description = "处理备注")
            })
    @PostMapping("/{id}/approve")
    public Result<Void> approveComplaint(@PathVariable Long id,
                                          @RequestParam(required = false) String adminNote) {
        OrderComplaint complaint = complaintRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "投诉不存在"));
        if (complaint.getStatus() != ComplaintStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "该投诉已处理，当前状态: " + complaint.getStatus().getDesc());
        }

        // 发起退款
        try {
            weChatPayService.refund(complaint.getOrderNum(), complaint.getReason().getDesc());
        } catch (Exception e) {
            log.error("退款失败，订单号: {}, err: {}", complaint.getOrderNum(), e.getMessage());
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.REFUND_FAILED,
                    "退款失败: " + e.getMessage());
        }

        // 更新投诉状态
        complaint.setStatus(ComplaintStatus.APPROVED);
        complaint.setAdminNote(adminNote);
        complaint.setResolveTime(LocalDateTime.now());
        complaintRepository.save(complaint);

        log.info("投诉已批准并退款，投诉ID: {}, 订单号: {}", id, complaint.getOrderNum());
        return Result.success("投诉已批准，退款已发起");
    }

    @OperationLog("驳回投诉")
    @Operation(summary = "驳回投诉",
            description = "驳回后订单恢复为已完成",
            parameters = {
                    @Parameter(name = "id", description = "投诉 ID", required = true),
                    @Parameter(name = "adminNote", description = "驳回理由", required = true)
            })
    @PostMapping("/{id}/reject")
    public Result<Void> rejectComplaint(@PathVariable Long id,
                                         @RequestParam String adminNote) {
        OrderComplaint complaint = complaintRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "投诉不存在"));
        if (complaint.getStatus() != ComplaintStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "该投诉已处理，当前状态: " + complaint.getStatus().getDesc());
        }

        // 订单恢复已完成
        orderRepository.findByOrderNum(complaint.getOrderNum()).ifPresent(order -> {
            order.setOrderStatus(OrderStatus.COMPLETED);
            orderRepository.save(order);
        });

        complaint.setStatus(ComplaintStatus.REJECTED);
        complaint.setAdminNote(adminNote);
        complaint.setResolveTime(LocalDateTime.now());
        complaintRepository.save(complaint);

        log.info("投诉已驳回，投诉ID: {}, 订单号: {}", id, complaint.getOrderNum());
        return Result.success("投诉已驳回");
    }
}

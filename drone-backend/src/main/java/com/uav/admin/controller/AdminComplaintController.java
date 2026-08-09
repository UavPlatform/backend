package com.uav.admin.controller;

import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.order.pojo.vo.ComplaintListVO;
import com.uav.order.service.OrderComplaintService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RequireRole;
import com.uav.server.enums.ComplaintStatus;
import com.uav.server.enums.Role;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin Complaint API", description = "管理端投诉处理")
@RestController
@RequestMapping("/admin/complaint")
@Slf4j
@RequiredArgsConstructor
@RequireRole({Role.RIDER, Role.ADMIN})
public class AdminComplaintController {

    private final OrderComplaintService complaintService;

    @OperationLog("查看投诉列表")
    @Operation(summary = "投诉列表", description = "分页查看投诉，可按状态筛选")
    @GetMapping("/list")
    public Result<ComplaintListVO> listComplaints(@RequestParam(required = false) ComplaintStatus status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        Page<OrderComplaint> complaintPage = complaintService.listComplaints(status, page, size);

        ComplaintListVO vo = new ComplaintListVO();
        vo.setComplaints(complaintPage.getContent());
        vo.setCurrentPage(complaintPage.getNumber());
        vo.setTotalPages(complaintPage.getTotalPages());
        vo.setTotalElements(complaintPage.getTotalElements());
        return Result.success(vo);
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
        complaintService.approveComplaint(id, adminNote);
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
        complaintService.rejectComplaint(id, adminNote);
        return Result.success("投诉已驳回");
    }
}

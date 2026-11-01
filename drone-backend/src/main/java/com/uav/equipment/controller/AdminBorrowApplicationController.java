package com.uav.equipment.controller;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.equipment.pojo.dto.BorrowApproveDTO;
import com.uav.equipment.pojo.dto.BorrowRejectDTO;
import com.uav.equipment.pojo.vo.AdminBorrowApplicationVO;
import com.uav.equipment.pojo.vo.DroneBorrowVO;
import com.uav.equipment.service.AdminBorrowApplicationService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** REQ-SUP-GOV-007: admin approve/reject borrow applications and assign SN. */
@Tag(name = "Admin Borrow Application API", description = "管理端借机申请审批（指定机身号或随机选一台；驳回写原因；误归还可改回）")
@RestController
@RequestMapping("/admin/borrow-applications")
@RequireRole(2)
public class AdminBorrowApplicationController {
    private final AdminBorrowApplicationService service;
    public AdminBorrowApplicationController(AdminBorrowApplicationService service) { this.service = service; }

    @Operation(operationId = "adminListBorrowApplications", summary = "借机申请列表",
            description = "可按 status、pilotId 过滤；默认全量，按申请时间倒序")
    @GetMapping
    public Result<AdminPageVo<AdminBorrowApplicationVO>> list(
            @Parameter(description = "状态过滤") @RequestParam(required = false) String status,
            @Parameter(description = "飞手 ID 过滤") @RequestParam(required = false) Long pilotId,
            @Parameter(description = "页码（从 0 起）") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数（默认 20，上限 100）") @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.list(status, pilotId, page, size));
    }

    @Operation(operationId = "adminApproveBorrowApplication", summary = "通过申请并指定机身号",
            description = "body 二选一：droneId=手选该机型在库启用飞机，或 random=true 随机一台；未指定不能通过；无在库时 409")
    @PostMapping("/{borrowId}/approve")
    public Result<DroneBorrowVO> approve(@PathVariable String borrowId, @Valid @RequestBody BorrowApproveDTO input) {
        return Result.success(service.approve(UserContext.getUserId(), borrowId, input));
    }

    @Operation(operationId = "adminRejectBorrowApplication", summary = "驳回申请", description = "必须填写原因")
    @PostMapping("/{borrowId}/reject")
    public Result<DroneBorrowVO> reject(@PathVariable String borrowId, @Valid @RequestBody BorrowRejectDTO input) {
        return Result.success(service.reject(UserContext.getUserId(), borrowId, input));
    }

    @Operation(operationId = "adminRestoreBorrowApplication", summary = "误归还改回已借出",
            description = "飞机仍在库时可把已归还记录改回已借出（REQ-SUP-GOV-007）")
    @PostMapping("/{borrowId}/restore")
    public Result<DroneBorrowVO> restore(@PathVariable String borrowId) {
        return Result.success(service.restore(UserContext.getUserId(), borrowId));
    }
}

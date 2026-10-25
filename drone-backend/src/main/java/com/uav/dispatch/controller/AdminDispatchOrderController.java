package com.uav.dispatch.controller;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.dispatch.pojo.vo.AdminDispatchOrderVO;
import com.uav.dispatch.service.AdminDispatchOrderService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** 管理端派单订单监管（只读，仅管理员）。替代旧撮合流程的 /admin/orders。 */
@Tag(name = "Admin Dispatch Order API", description = "管理端派单订单查询（只读，仅管理员）")
@RestController
@RequestMapping("/admin/dispatch/orders")
@RequireRole(2)
public class AdminDispatchOrderController {
    private final AdminDispatchOrderService service;

    public AdminDispatchOrderController(AdminDispatchOrderService service) {
        this.service = service;
    }

    @Operation(operationId = "adminListDispatchOrders", summary = "派单订单分页列表", description = "按创建时间倒序；page 从 0 起，size 上限 100")
    @GetMapping
    public Result<AdminPageVo<AdminDispatchOrderVO.Row>> list(
            @Parameter(description = "页码（从 0 起）") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数（默认 20，上限 100）") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "订单阶段：AWAITING_DEPOSIT / WAITING_ACCEPTANCE / PILOT_PREPARING / PILOT_ARRIVED / IN_TRANSIT / DELIVERY_CONFIRMATION / COMPLETED / CANCELLED；未知值 400") @RequestParam(required = false) String stage,
            @Parameter(description = "资金状态：UNPAID / PAID / BALANCE_DUE / SETTLED / REFUNDED；未知值 400") @RequestParam(required = false) String fundsStatus,
            @Parameter(description = "创建时间下限（含），ISO-8601") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @Parameter(description = "创建时间上限（不含），ISO-8601") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
            @Parameter(description = "关键字：订单号、货主或飞手用户名模糊匹配") @RequestParam(required = false) String keyword) {
        return Result.success(service.list(page, size, stage, fundsStatus, createdFrom, createdTo, keyword));
    }

    @Operation(operationId = "adminGetDispatchOrder", summary = "派单订单详情", description = "订单视图（含运单、账单、实际执行无人机）、参与方、状态时间线、派单记录、连机记录")
    @GetMapping("/{orderId}")
    public Result<AdminDispatchOrderVO.Detail> detail(@PathVariable String orderId) {
        return Result.success(service.detail(orderId));
    }
}

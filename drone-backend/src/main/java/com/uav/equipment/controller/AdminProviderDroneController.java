package com.uav.equipment.controller;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.equipment.pojo.vo.AdminProviderDroneVO;
import com.uav.equipment.service.AdminProviderDroneService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端只读查看服务商无人机与机场（ADR-0008；不提供机场增删改）。 */
@Tag(name = "Admin Provider Drone API", description = "管理端服务商无人机/机场查询（只读，仅管理员）")
@RestController
@RequestMapping("/admin/provider/drones")
@RequireRole(2)
public class AdminProviderDroneController {
    private final AdminProviderDroneService service;

    public AdminProviderDroneController(AdminProviderDroneService service) {
        this.service = service;
    }

    @Operation(operationId = "adminListProviderDrones", summary = "服务商无人机/机场分页列表", description = "按登记时间倒序；page 从 0 起，size 上限 100；含已停用设备与服务商")
    @GetMapping
    public Result<AdminPageVo<AdminProviderDroneVO>> list(
            @Parameter(description = "页码（从 0 起）") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数（默认 20，上限 100）") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "服务商 ID（正整数，否则 400）") @RequestParam(required = false) Long providerId,
            @Parameter(description = "机场名称，模糊匹配（最长 100 字）") @RequestParam(required = false) String airportName) {
        return Result.success(service.list(page, size, providerId, airportName));
    }
}

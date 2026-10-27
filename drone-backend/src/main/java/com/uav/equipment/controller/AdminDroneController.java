package com.uav.equipment.controller;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.equipment.pojo.dto.EquipmentEnabledDTO;
import com.uav.equipment.pojo.dto.ProviderDroneDTO;
import com.uav.equipment.pojo.vo.AdminDroneBorrowVO;
import com.uav.equipment.pojo.vo.AdminDroneVO;
import com.uav.equipment.service.AdminDroneService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** ADR-0011 / ADR-0012 / REQ-SUP-GOV-005: admin drone CRUD; airport is seeded default (no airport admin UI). */
@Tag(name = "Admin Drone API", description = "管理端机场/无人机管理（仅管理员；无人机归平台所有，隐式绑定默认机场）")
@RestController
@RequestMapping("/admin/drones")
@RequireRole(2)
public class AdminDroneController {
    private final AdminDroneService service;

    public AdminDroneController(AdminDroneService service) { this.service = service; }

    @Operation(operationId = "adminListDrones", summary = "无人机分页列表",
            description = "按登记时间倒序；page 从 0 起，size 上限 100；含已停用设备。每行含所属机场名、在库/已借出、执飞中与最近订单")
    @GetMapping
    public Result<AdminPageVo<AdminDroneVO>> list(
            @Parameter(description = "页码（从 0 起）") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数（默认 20，上限 100）") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "机场名称，模糊匹配（最长 100 字）") @RequestParam(required = false) String airportName) {
        return Result.success(service.list(page, size, airportName));
    }

    @Operation(operationId = "adminCreateDrone", summary = "登记无人机",
            description = "机型必选（aircraft_model 中存在且启用）；机身号必填、去空格转大写后全局唯一（重复 409）；所属机场隐式绑定平台默认机场（ADR-0012），请求体不含机场字段")
    @PostMapping
    public Result<AdminDroneVO> create(@Valid @RequestBody ProviderDroneDTO input) {
        return Result.success(service.create(input));
    }

    @Operation(operationId = "adminGetDrone", summary = "无人机详情")
    @GetMapping("/{droneId}")
    public Result<AdminDroneVO> get(@PathVariable String droneId) {
        return Result.success(service.get(droneId));
    }

    @Operation(operationId = "adminUpdateDrone", summary = "编辑无人机",
            description = "可改机型与机身号（借出期间或已有借还记录后不能改身份，409）；机场绑定不变（本版无机场管理）")
    @PutMapping("/{droneId}")
    public Result<AdminDroneVO> update(@PathVariable String droneId, @Valid @RequestBody ProviderDroneDTO input) {
        return Result.success(service.update(droneId, input));
    }

    @Operation(operationId = "adminSetDroneEnabled", summary = "启用/停用无人机",
            description = "停用后飞手不可借用；正被借出的仍保留借用记录并可由原飞手归还")
    @PostMapping("/{droneId}/enabled")
    public Result<AdminDroneVO> enabled(@PathVariable String droneId, @Valid @RequestBody EquipmentEnabledDTO input) {
        return Result.success(service.enabled(droneId, input.enabled()));
    }

    @Operation(operationId = "adminListDroneBorrows", summary = "无人机借还记录", description = "按借出时间倒序；page 从 0 起，size 上限 100")
    @GetMapping("/{droneId}/borrows")
    public Result<AdminPageVo<AdminDroneBorrowVO>> borrows(
            @PathVariable String droneId,
            @Parameter(description = "页码（从 0 起）") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数（默认 20，上限 100）") @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.borrows(droneId, page, size));
    }
}

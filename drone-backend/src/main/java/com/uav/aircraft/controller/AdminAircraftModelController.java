package com.uav.aircraft.controller;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.aircraft.pojo.dto.AdminAircraftModelDTO;
import com.uav.aircraft.pojo.dto.AircraftModelEnabledDTO;
import com.uav.aircraft.pojo.vo.AdminAircraftModelVO;
import com.uav.aircraft.service.AdminAircraftModelService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** REQ-SUP-GOV-006: 管理员维护机型列表与机型参数（增改列表、启停、删除未引用机型）。 */
@Tag(name = "Admin Aircraft Model API", description = "管理端机型管理（仅管理员）")
@RestController
@RequestMapping("/admin/aircraft-models")
@RequireRole(2)
public class AdminAircraftModelController {
    private final AdminAircraftModelService service;

    public AdminAircraftModelController(AdminAircraftModelService service) {
        this.service = service;
    }

    @Operation(operationId = "adminListAircraftModels", summary = "机型分页列表",
            description = "按型号代码升序；page 从 0 起，size 上限 100；含已停用机型。公开启用目录仍走 GET /api/aircraft-models")
    @GetMapping
    public Result<AdminPageVo<AdminAircraftModelVO>> list(
            @Parameter(description = "页码（从 0 起）") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数（默认 20，上限 100）") @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.list(page, size));
    }

    @Operation(operationId = "adminCreateAircraftModel", summary = "新增机型",
            description = "型号代码全局唯一（大小写不敏感，重复 409）；默认启用；coefficient 固定为 1.000（本版不开放改口）")
    @PostMapping
    public Result<AdminAircraftModelVO> create(@Valid @RequestBody AdminAircraftModelDTO input) {
        return Result.success(service.create(input));
    }

    @Operation(operationId = "adminGetAircraftModel", summary = "机型详情")
    @GetMapping("/{modelId}")
    public Result<AdminAircraftModelVO> get(@PathVariable Long modelId) {
        return Result.success(service.get(modelId));
    }

    @Operation(operationId = "adminUpdateAircraftModel", summary = "编辑机型",
            description = "可改型号代码、显示名、最大载重、是否吊运；型号代码冲突 409；不改 coefficient 与 enabled")
    @PutMapping("/{modelId}")
    public Result<AdminAircraftModelVO> update(@PathVariable Long modelId, @Valid @RequestBody AdminAircraftModelDTO input) {
        return Result.success(service.update(modelId, input));
    }

    @Operation(operationId = "adminSetAircraftModelEnabled", summary = "启用/停用机型",
            description = "停用后登记无人机与公开目录都选不到；已引用该机型的无人机/资质/执飞记录仍照常显示")
    @PostMapping("/{modelId}/enabled")
    public Result<AdminAircraftModelVO> enabled(@PathVariable Long modelId,
                                                @Valid @RequestBody AircraftModelEnabledDTO input) {
        return Result.success(service.enabled(modelId, input.enabled()));
    }

    @Operation(operationId = "adminDeleteAircraftModel", summary = "删除机型",
            description = "仅当从未被无人机、飞手资质/绑机、执飞连接或旧应征引用时可删；否则 409，提示改为停用")
    @DeleteMapping("/{modelId}")
    public Result<Void> delete(@PathVariable Long modelId) {
        service.delete(modelId);
        return Result.success();
    }
}

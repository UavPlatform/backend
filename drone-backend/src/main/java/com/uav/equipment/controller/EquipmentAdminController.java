package com.uav.equipment.controller;

import com.uav.equipment.pojo.dto.*;
import com.uav.equipment.pojo.vo.*;
import com.uav.equipment.service.*;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/equipment/admin") @RequireRole(2)
@Tag(name = "Equipment Administration API")
public class EquipmentAdminController {
    private final EquipmentProviderService accounts;
    private final EquipmentCatalogService catalog;
    private final DroneBorrowService borrows;
    public EquipmentAdminController(EquipmentProviderService accounts, EquipmentCatalogService catalog, DroneBorrowService borrows) {
        this.accounts = accounts; this.catalog = catalog; this.borrows = borrows;
    }
    @PostMapping("/providers") @Operation(summary = "平台开通服务商账号", description = "仅现有管理员；密码仅以BCrypt保存，响应不含密码或哈希；无公开注册入口")
    public Result<ProviderAccountVO> provisionProvider(@Valid @RequestBody ProviderProvisionDTO input) {
        return Result.success(accounts.provision(input.username(), input.password(), input.displayName()));
    }
    @GetMapping("/providers") @Operation(summary = "分页读取服务商账号")
    public Result<ProviderAccountPageVO> equipmentProviders(@RequestParam(defaultValue = "0") String page) { return Result.success(accounts.list(EquipmentAccess.parse(page))); }
    @PostMapping("/providers/{providerId}/enabled") @Operation(summary = "启停服务商账号", description = "停用后既有令牌不能再管理设备；已借设备仍可由原飞手归还")
    public Result<ProviderAccountVO> providerEnabled(@PathVariable Long providerId, @Valid @RequestBody EquipmentEnabledDTO input) { return Result.success(accounts.enabled(providerId, input.enabled())); }
    @GetMapping("/drones") @Operation(summary = "监管查看全部服务商设备登记")
    public Result<ProviderDronePageVO> equipmentAdminDrones(@RequestParam(defaultValue = "0") String page) { return Result.success(catalog.admin(EquipmentAccess.parse(page))); }
    @GetMapping("/borrows") @Operation(summary = "监管查看全部借还登记")
    public Result<DroneBorrowPageVO> equipmentAdminBorrows(@RequestParam(defaultValue = "0") String page) { return Result.success(borrows.admin(EquipmentAccess.parse(page))); }
}

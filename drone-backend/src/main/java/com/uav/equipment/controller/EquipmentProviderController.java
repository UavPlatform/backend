package com.uav.equipment.controller;

import com.uav.equipment.pojo.dto.*;
import com.uav.equipment.pojo.vo.*;
import com.uav.equipment.service.*;
import com.uav.server.annotation.*;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/equipment/provider") @RequireRole(3)
@Tag(name = "Equipment Provider API", description = "平台开通的独立服务商账号，仅管理本机构设备及借还记录；不接单、不飞控")
public class EquipmentProviderController {
    private final EquipmentProviderService accounts;
    private final EquipmentCatalogService catalog;
    private final DroneBorrowService borrows;
    public EquipmentProviderController(EquipmentProviderService accounts, EquipmentCatalogService catalog, DroneBorrowService borrows) {
        this.accounts = accounts; this.catalog = catalog; this.borrows = borrows;
    }
    @SkipJwt @RateLimiter(limit = 5, windowSeconds = 60)
    @PostMapping("/login") @Operation(summary = "服务商登录", description = "账号由平台管理员开通，无自助注册；返回独立role=3访问令牌，不可用于旧用户、监管和飞控接口")
    public Result<ProviderLoginVO> providerLogin(@Valid @RequestBody ProviderLoginDTO input) { return Result.success(accounts.login(input)); }
    @GetMapping("/me") @Operation(summary = "读取本人服务商资料")
    public Result<ProviderAccountVO> providerMe() { return Result.success(accounts.me(UserContext.getUserId())); }
    @GetMapping("/drones") @Operation(summary = "分页读取本机构无人机")
    public Result<ProviderDronePageVO> providerDrones(@RequestParam(defaultValue = "0") String page) { return Result.success(catalog.mine(UserContext.getUserId(), EquipmentAccess.parse(page))); }
    @PostMapping("/drones") @Operation(summary = "登记本机构无人机", description = "真实机身号全局唯一，选择现有启用机型并登记机场；不产生费用、不创建旧绑定记录")
    public Result<ProviderDroneVO> registerProviderDrone(@Valid @RequestBody ProviderDroneDTO input) { return Result.success(catalog.register(UserContext.getUserId(), input)); }
    @PutMapping("/drones/{droneId}") @Operation(summary = "修改本机构无人机登记", description = "借出期间不可修改；产生借还记录后机身号和机型不可改写，以保留设备身份历史；可在归还后更新机场资料")
    public Result<ProviderDroneVO> updateProviderDrone(@PathVariable String droneId, @Valid @RequestBody ProviderDroneDTO input) {
        return Result.success(catalog.update(UserContext.getUserId(), droneId, input));
    }
    @PostMapping("/drones/{droneId}/enabled") @Operation(summary = "启停本机构无人机借用", description = "停用不替飞手归还已借设备，仍允许原飞手完成归还")
    public Result<ProviderDroneVO> providerDroneEnabled(@PathVariable String droneId, @Valid @RequestBody EquipmentEnabledDTO input) {
        return Result.success(catalog.enabled(UserContext.getUserId(), droneId, input.enabled()));
    }
    @GetMapping("/borrows") @Operation(summary = "分页读取本机构借还记录", description = "仅本人机构登记的设备；借还不关联订单、收费或押金")
    public Result<DroneBorrowPageVO> providerBorrows(@RequestParam(defaultValue = "0") String page) { return Result.success(borrows.provider(UserContext.getUserId(), EquipmentAccess.parse(page))); }
}

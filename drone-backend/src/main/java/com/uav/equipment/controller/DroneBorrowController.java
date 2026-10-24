package com.uav.equipment.controller;

import com.uav.equipment.pojo.dto.DroneBorrowDTO;
import com.uav.equipment.pojo.vo.*;
import com.uav.equipment.service.*;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/equipment/pilot") @RequireRole(1)
@Tag(name = "Drone Borrowing API", description = "真实借还登记；不绑定飞手、不关联订单、不收费、不收押金")
public class DroneBorrowController {
    private final EquipmentCatalogService catalog;
    private final DroneBorrowService borrows;
    public DroneBorrowController(EquipmentCatalogService catalog, DroneBorrowService borrows) { this.catalog = catalog; this.borrows = borrows; }
    @GetMapping("/drones") @Operation(summary = "分页读取可借设备目录", description = "只列启用服务商、设备和机型；已借出显示不可借，不返回其他飞手的借用标识或身份")
    public Result<ProviderDronePageVO> pilotEquipment(@RequestParam(required = false) Long providerId, @RequestParam(defaultValue = "0") String page) {
        return Result.success(catalog.catalog(UserContext.getUserId(), providerId, EquipmentAccess.parse(page)));
    }
    @GetMapping("/borrows") @Operation(summary = "分页读取本人借还记录")
    public Result<DroneBorrowPageVO> pilotBorrows(@RequestParam(defaultValue = "0") String page) { return Result.success(borrows.mine(UserContext.getUserId(), EquipmentAccess.parse(page))); }
    @PostMapping("/borrows") @Operation(summary = "登记借用无人机", description = "请求标识按当前飞手幂等；同一设备并发只能借出一次，不接收用户ID、订单或金额")
    public Result<DroneBorrowVO> borrowDrone(@Valid @RequestBody DroneBorrowDTO input) { return Result.success(borrows.borrow(UserContext.getUserId(), input)); }
    @PostMapping("/borrows/{borrowId}/return") @Operation(summary = "登记归还本人借用设备", description = "按原借用ID归还，重复请求幂等，旧归还重试不影响后续借用；设备或服务商停用不阻止本人归还")
    public Result<DroneBorrowVO> returnDrone(@PathVariable String borrowId) { return Result.success(borrows.giveBack(UserContext.getUserId(), borrowId)); }
}

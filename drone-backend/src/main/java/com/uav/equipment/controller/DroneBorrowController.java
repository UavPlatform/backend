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

/** REQ-PILOT-EXEC-005: apply by model → wait approval → return without re-approval. */
@RestController @RequestMapping("/equipment/pilot") @RequireRole(1)
@Tag(name = "Drone Borrowing API", description = "飞手借机申请与归还；选机型申请，管理员指定机身号后借出；归还不需审批")
public class DroneBorrowController {
    private final EquipmentCatalogService catalog;
    private final DroneBorrowService borrows;
    public DroneBorrowController(EquipmentCatalogService catalog, DroneBorrowService borrows) {
        this.catalog = catalog; this.borrows = borrows;
    }
    @GetMapping("/drones") @Operation(summary = "分页读取可借设备目录",
            description = "只列启用的设备和机型；已借出显示不可借。主路径请用机型在库概况 + 申请接口。")
    public Result<ProviderDronePageVO> pilotEquipment(@RequestParam(defaultValue = "0") String page) {
        return Result.success(catalog.catalog(UserContext.getUserId(), EquipmentAccess.parse(page)));
    }
    @GetMapping("/model-stock") @Operation(operationId = "pilotModelStock", summary = "机型在库概况",
            description = "启用机型及其当前在库启用飞机数量；数量为 0 的机型不能申请")
    public Result<ModelStockVO> modelStock() { return Result.success(borrows.modelStock()); }
    @GetMapping("/borrows") @Operation(summary = "分页读取本人申请/借还记录")
    public Result<DroneBorrowPageVO> pilotBorrows(@RequestParam(defaultValue = "0") String page) {
        return Result.success(borrows.mine(UserContext.getUserId(), EquipmentAccess.parse(page)));
    }
    @GetMapping("/borrows/current") @Operation(operationId = "pilotCurrentBorrows", summary = "当前已借出的飞机",
            description = "默认一人一台；管理员特批后可能多于一台")
    public Result<DroneBorrowPageVO> currentBorrows() {
        return Result.success(borrows.current(UserContext.getUserId()));
    }
    @PostMapping("/borrows") @Operation(summary = "提交借机申请",
            description = "只选机型；订单可选关联（不绑死）；不选机身号。requestKey 按飞手幂等。"
                    + "已有已借出时可再提交（alreadyHasActiveLoan=true），由管理员决定是否特批。"
                    + "该机型无在库启用飞机时 409。")
    public Result<DroneBorrowVO> borrowDrone(@Valid @RequestBody DroneBorrowDTO input) {
        DroneBorrowVO vo = borrows.apply(UserContext.getUserId(), input);
        if (vo.alreadyHasActiveLoan()) {
            return Result.success("您已有已借出的飞机，建议先归还；申请已提交，等待管理员审批", vo);
        }
        return Result.success(vo);
    }
    @PostMapping("/borrows/{borrowId}/return") @Operation(summary = "确认归还本人已借出设备",
            description = "立即生效，不需管理员再批；重复请求幂等；设备停用不阻止本人归还")
    public Result<DroneBorrowVO> returnDrone(@PathVariable String borrowId) {
        return Result.success(borrows.giveBack(UserContext.getUserId(), borrowId));
    }
}

package com.uav.address.controller;

import com.uav.address.pojo.dto.AddressDto;
import com.uav.address.pojo.entity.Address;
import com.uav.address.service.AddressService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequireRole({0, 2})
@Tag(name = "Address API", description = "用户收货地址簿接口")
@RestController
@RequestMapping("/address")
@Slf4j
public class AddressController {

    @Autowired
    private AddressService addressService;

    @OperationLog("查询地址列表")
    @Operation(summary = "地址列表", description = "当前用户的收货地址列表，默认地址在前")
    @GetMapping("/list")
    public Result<List<Address>> list() {
        Long userId = UserContext.getUserId();
        return Result.success("获取成功", addressService.list(userId));
    }

    @OperationLog("新增地址")
    @Operation(summary = "新增地址", description = "新增收货地址（含联系人、电话、省市区、详细地址、经纬度）")
    @PostMapping("/create")
    public Result<Address> create(@RequestBody AddressDto dto) {
        Long userId = UserContext.getUserId();
        return Result.success("新增成功", addressService.create(userId, dto));
    }

    @OperationLog("编辑地址")
    @Operation(summary = "编辑地址", description = "编辑收货地址，只能编辑自己的地址")
    @PutMapping("/update")
    public Result<Address> update(@RequestBody AddressDto dto) {
        Long userId = UserContext.getUserId();
        return Result.success("更新成功", addressService.update(userId, dto));
    }

    @OperationLog("删除地址")
    @Operation(summary = "删除地址", description = "删除收货地址，只能删除自己的地址",
            parameters = {@Parameter(name = "id", description = "地址ID", required = true)})
    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam Long id) {
        Long userId = UserContext.getUserId();
        addressService.delete(userId, id);
        return Result.success("删除成功");
    }

    @OperationLog("设为默认地址")
    @Operation(summary = "设为默认", description = "设为默认地址，同用户其他默认地址自动取消",
            parameters = {@Parameter(name = "id", description = "地址ID", required = true)})
    @PutMapping("/default")
    public Result<Void> setDefault(@RequestParam Long id) {
        Long userId = UserContext.getUserId();
        addressService.setDefault(userId, id);
        return Result.success("设置成功");
    }
}

package com.uav.rider.controller;

import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.rider.pojo.vo.RiderInfoVO;
import com.uav.rider.service.RiderInfoService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.enums.Role;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rider/info")
public class RiderInfoController {

    @Autowired
    private RiderInfoService riderInfoService;

    @GetMapping
    public Result<RiderInfoVO> getInfo() {
        Long userId = UserContext.getUserId();
        return Result.success(riderInfoService.getInfo(userId));
    }

    @RequireRole(Role.RIDER)
    @PatchMapping
    public Result<RiderInfoVO> editInfo(@Valid @RequestBody RiderInfoDto dto) {
        Long userId = UserContext.getUserId();
        return Result.success("保存成功", riderInfoService.editInfo(userId, dto));
    }
}

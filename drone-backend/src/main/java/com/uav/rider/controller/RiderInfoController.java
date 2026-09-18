package com.uav.rider.controller;

import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Rider Info API", description = "飞手资料维护（占位实现，尚未接入持久化）")
@RestController
@RequestMapping("/info")
public class RiderInfoController {

    @Operation(summary = "编辑本人资料", description = "飞手编辑个人资料；当前为占位实现，仅返回成功")
    @PostMapping("/edit")
    public Result<Void> editSelfInfo(@RequestBody RiderInfoDto riderInfoDto) {
        return Result.success();
    }
}

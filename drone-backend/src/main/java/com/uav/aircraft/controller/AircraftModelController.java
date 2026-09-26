package com.uav.aircraft.controller;

import com.uav.aircraft.pojo.vo.AircraftModelVO;
import com.uav.aircraft.service.AircraftModelService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台机型目录查询（REQ-BACKEND-001 机型库）：飞手绑机时选择机型，
 * 用户/飞手端展示机型名称与载重、系数等关键参数。
 */
@Tag(name = "Aircraft Model API", description = "平台机型目录接口")
@RestController
@RequestMapping("/api/aircraft-models")
public class AircraftModelController {

    @Autowired
    private AircraftModelService aircraftModelService;

    @OperationLog("查询机型目录")
    @Operation(summary = "机型目录", description = "平台机型目录（仅启用机型）：型号、显示名、最大载重、机型系数、是否可吊运")
    @GetMapping
    public Result<List<AircraftModelVO>> listAircraftModels() {
        return Result.success(aircraftModelService.listEnabled().stream()
                .map(AircraftModelVO::from)
                .toList());
    }
}

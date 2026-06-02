package com.drone.controller.uav;

import com.drone.pojo.entity.Uav;
import com.drone.pojo.result.Result;
import com.drone.pojo.vo.admin.AdminStatisticsVO;
import com.drone.pojo.vo.admin.LiveUavVO;
import com.drone.server.annotation.OperationLog;
import com.drone.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "UAV Management API")
@RestController
@RequestMapping("/admin/uav")
@Slf4j
public class UavManagementController {

    @Autowired
    private AdminService adminService;

    @OperationLog("修改无人机可用状态")
    @Operation(
            summary = "修改无人机可用状态",
            description = "管理端修改无人机的可用状态",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "修改成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "无人机不存在"
                    )
            }
    )
    @PostMapping("/available")
    public Result<Void> updateUavAvailable(@RequestParam String deviceId, @RequestParam Character isAvailable) {
        boolean updated = adminService.updateUavAvailable(deviceId, isAvailable);
        return updated ? Result.success("修改成功") : Result.fail("修改失败");
    }

    @OperationLog("查询所有无人机")
    @Operation(
            summary = "查询所有无人机详细信息",
            description = "管理端查询所有无人机的完整详细信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": [{\"id\": 1, \"uavName\": \"无人机1\", \"djiId\": \"123456\"}]}"
                                    )
                            )
                    )
            }
    )
    @GetMapping
    public Result<List<Uav>> getAllUav() {
        List<Uav> uavs = adminService.getUav();
        return Result.success(uavs);
    }

    @OperationLog("查询直播无人机")
    @Operation(
            summary = "查询当前正在直播的无人机",
            description = "管理端查询当前正在直播的无人机列表",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": [{\"deviceId\": \"123456\", \"uavName\": \"无人机1\"}]}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/live")
    public Result<List<LiveUavVO>> getLiveUav() {
        List<LiveUavVO> liveUavList = adminService.getLiveUav();
        return Result.success(liveUavList);
    }

    @OperationLog("查询无人机详情")
    @Operation(
            summary = "查询单个无人机详情",
            description = "根据设备ID查询无人机的详细信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": {\"id\": 1, \"uavName\": \"无人机1\"}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "无人机不存在"
                    )
            }
    )
    @GetMapping("/detail")
    public Result<Uav> getUavDetail(@RequestParam String deviceId) {
        Uav uav = adminService.getUavByDeviceId(deviceId);
        return Result.success(uav);
    }

    @OperationLog("查询管理统计")
    @Operation(
            summary = "获取管理员统计信息",
            description = "获取系统的统计信息，包括无人机总数、在线数、直播数等",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": {\"totalUavs\": 10, \"onlineUavs\": 5}}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/statistics")
    public Result<AdminStatisticsVO> getStatistics() {
        AdminStatisticsVO statistics = adminService.getAdminStatistics();
        return Result.success(statistics);
    }
}

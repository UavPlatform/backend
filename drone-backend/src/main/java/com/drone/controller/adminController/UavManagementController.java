package com.drone.controller.adminController;

import com.drone.pojo.entity.Uav;
import com.drone.pojo.result.Result;
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
import java.util.Map;

@Tag(name = "UAV Management API")
@RestController
@RequestMapping("/admin/uav")
@Slf4j
public class UavManagementController {

    @Autowired
    private AdminService adminService;

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
                                            example = "{\"success\": true, \"message\": \"修改成功\"}"
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
    public Result<?> updateUavAvailable(@RequestParam String deviceId, @RequestParam Character isAvailable){
        log.info("修改无人机ID：{}的可用状态为：{}", deviceId, isAvailable);
        try {
            boolean updated = adminService.updateUavAvailable(deviceId, isAvailable);
            return updated ? Result.success("修改成功") : Result.fail("修改失败");
        } catch (IllegalArgumentException e) {
            log.error("参数错误: {}", e.getMessage());
            return Result.fail(400, e.getMessage());
        } catch (RuntimeException e) {
            log.error("修改无人机可用状态失败: {}", e.getMessage());
            return Result.fail(404, e.getMessage());
        } catch (Exception e) {
            log.error("修改无人机可用状态失败: {}", e.getMessage());
            return Result.fail(500, "修改失败，请稍后重试");
        }
    }

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
                                            example = "{\"success\": true, \"data\": [{\"id\": 1, \"uavName\": \"无人机1\", \"onlineStatus\": \"1\", \"uavCreateTime\": \"2026-03-31T10:00:00\", \"djiId\": \"123456\", \"controllerModel\": \"Mavic 3\", \"isAvailable\": \"1\"}]}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "查询失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"查询失败\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping
    public Result<?> getAllUav(){
        log.info("管理端查询所有无人机详细信息");
        try {
            var uavs = adminService.getUav();
            if (uavs == null || uavs.length == 0) {
                return Result.fail("系统暂时没有录入无人机");
            } else {
                return Result.success(uavs);
            }
        } catch (Exception e) {
            log.error("查询无人机失败: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

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
                                            example = "{\"success\": true, \"data\": [{\"deviceId\": \"123456\", \"uavName\": \"无人机1\", \"roomId\": \"room1\", \"requestId\": \"req1\", \"updatedAt\": 1620000000000, \"onlineStatus\": \"1\", \"isAvailable\": \"1\"}]}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "查询失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"查询失败\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/live")
    public Result<?> getLiveUav(){
        log.info("管理端查询当前正在直播的无人机");
        try {
            List<Map<String, Object>> liveUavList = adminService.getLiveUav();
            if (liveUavList == null || liveUavList.isEmpty()) {
                return Result.fail("当前没有正在直播的无人机");
            } else {
                return Result.success(liveUavList);
            }
        } catch (Exception e) {
            log.error("查询直播无人机失败: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

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
                                            example = "{\"success\": true, \"data\": {\"id\": 1, \"uavName\": \"无人机1\", \"onlineStatus\": \"1\", \"uavCreateTime\": \"2026-03-31T10:00:00\", \"djiId\": \"123456\", \"controllerModel\": \"Mavic 3\", \"isAvailable\": \"1\"}}"
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
    public Result<Uav> getUavDetail(@RequestParam String deviceId){
        log.info("管理端查询无人机详情，设备ID：{}", deviceId);
        try {
            Uav uav = adminService.getUavByDeviceId(deviceId);
            return Result.success(uav);
        } catch (IllegalArgumentException e) {
            log.error("参数错误: {}", e.getMessage());
            return Result.fail(400, e.getMessage());
        } catch (RuntimeException e) {
            log.error("查询无人机详情失败: {}", e.getMessage());
            return Result.fail(404, e.getMessage());
        } catch (Exception e) {
            log.error("查询无人机详情失败: {}", e.getMessage());
            return Result.fail(500, e.getMessage());
        }
    }

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
                                            example = "{\"success\": true, \"data\": {\"totalUavs\": 10, \"onlineUavs\": 5, \"availableUavs\": 8, \"liveUavs\": 2, \"offlineUavs\": 5, \"unavailableUavs\": 2, \"totalUsers\": 4}}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/statistics")
    public Result<Map<String, Object>> getStatistics(){
        log.info("管理端查询统计信息");
        try {
            Map<String, Object> statistics = adminService.getAdminStatistics();
            return Result.success(statistics);
        } catch (Exception e) {
            log.error("获取统计信息失败: {}", e.getMessage());
            return Result.fail(500, e.getMessage());
        }
    }
}

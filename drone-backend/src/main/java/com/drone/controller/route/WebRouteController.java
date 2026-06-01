package com.drone.controller.route;

import com.drone.pojo.dto.RouteDto;
import com.drone.pojo.entity.Route;
import com.drone.pojo.result.Result;
import com.drone.server.annotation.RateLimiter;
import com.drone.server.config.AmapConfig;
import com.drone.server.exception.BusinessException;
import com.drone.server.util.LogMaskUtil;
import com.drone.server.util.UserContext;
import com.drone.server.ws.handler.WsCommandAckResult;
import com.drone.service.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Route API", description = "航线规划接口")
@RestController
@RequestMapping("/route")
@Slf4j
public class WebRouteController {

    @Autowired
    private AmapConfig amapConfig;

    @Autowired
    private RouteService routeService;

    @Operation(
            summary = "获取高德地图配置",
            description = "获取前端加载高德地图所需的 key 和安全密钥",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": {\"key\": \"your_key\", \"securityKey\": \"your_key\"}}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/init")
    public Result<Map<String, Object>> init() {
        log.info("获取高德地图配置");
        Map<String, Object> result = new HashMap<>();
        result.put("key", amapConfig.getKey());
        result.put("securityKey", amapConfig.getSecurityKey());
        return Result.success(result);
    }

    @Operation(
            summary = "保存航线",
            description = "创建新航线，包含航点信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "保存成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"航线保存成功\", "
                                                    + "\"data\": {\"id\": 15, \"routeNum\": \"RT20260525143022zhan0001\", "
                                                    + "\"message\": \"航线保存成功\"}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "参数不合法",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 400, "
                                                    + "\"errorCode\": \"INVALID_PARAM\", "
                                                    + "\"message\": \"航线名称不能为空\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录或 token 过期",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 401, "
                                                    + "\"errorCode\": \"UNAUTHORIZED\", "
                                                    + "\"message\": \"Missing token\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "429",
                            description = "触发限流",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 429, "
                                                    + "\"errorCode\": \"RATE_LIMITED\", "
                                                    + "\"message\": \"请求过于频繁，请稍后重试\", \"data\": null}"
                                    )
                            )
                    )
            }
    )
    @RateLimiter(limit = 10, windowSeconds = 60)
    @PostMapping("/save")
    public Result<Map<String, Object>> saveRoute(@RequestBody RouteDto routeDto) {
        String userName = currentUserName();
        log.info("用户: {} 尝试保存航线: {}", maskedName(), routeDto.getRouteName());
        try {
            Route saved = routeService.saveRoute(routeDto, userName);
            log.info("航线保存成功，编号: {}", saved.getRouteNum());

            Map<String, Object> result = new HashMap<>();
            result.put("message", "航线保存成功");
            result.put("id", saved.getId());
            result.put("routeNum", saved.getRouteNum());
            return Result.success("航线保存成功", result);
        } catch (BusinessException e) {
            log.warn("航线保存失败，用户: {}, 原因: {}", maskedName(), e.getMessage());
            throw e;
        }
    }

    @Operation(
            summary = "获取当前用户的航线列表",
            description = "获取当前登录用户创建的所有航线，支持分页",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"获取成功\", "
                                                    + "\"data\": {\"routes\": [], \"currentPage\": 0, "
                                                    + "\"totalPages\": 1, \"totalElements\": 0}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录或 token 过期",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 401, "
                                                    + "\"errorCode\": \"UNAUTHORIZED\", "
                                                    + "\"message\": \"Missing token\", \"data\": null}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/list")
    public Result<Map<String, Object>> listRoutes(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        String userName = currentUserName();
        log.info("用户: {} 查询航线列表，第{}页/每页{}条", maskedName(), page, size);
        try {
            Page<Route> routePage = routeService.getRoutesByUser(userName, page, size);
            List<Route> routes = routePage.getContent();
            log.info("航线列表查询成功，用户: {}, 共 {} 条", maskedName(), routes.size());

            Map<String, Object> result = new HashMap<>();
            result.put("routes", routes);
            result.put("currentPage", routePage.getNumber());
            result.put("totalPages", routePage.getTotalPages());
            result.put("totalElements", routePage.getTotalElements());
            return Result.success("获取成功", result);
        } catch (BusinessException e) {
            log.warn("航线列表查询失败，用户: {}, 原因: {}", maskedName(), e.getMessage());
            throw e;
        }
    }

    @Operation(
            summary = "根据无人机ID获取航线列表",
            description = "获取指定无人机关联的航线列表",
            parameters = {
                    @Parameter(name = "djiId", description = "无人机ID", required = true)
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"获取成功\", "
                                                    + "\"data\": {\"routes\": []}}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/listByDjiId")
    public Result<Map<String, Object>> listRoutesByDjiId(@RequestParam String djiId) {
        log.info("查询无人机 {} 的航线列表", djiId);
        try {
            List<Route> routes = routeService.getRoutesByDjiId(djiId);
            log.info("无人机 {} 航线列表查询成功，共 {} 条", djiId, routes.size());

            Map<String, Object> result = new HashMap<>();
            result.put("routes", routes);
            return Result.success("获取成功", result);
        } catch (BusinessException e) {
            log.warn("无人机 {} 航线列表查询失败，原因: {}", djiId, e.getMessage());
            throw e;
        }
    }

    @Operation(
            summary = "获取航线详情",
            description = "根据航线业务编号获取航线详细信息，包含航点列表",
            parameters = {
                    @Parameter(name = "routeNum", description = "航线业务编号", required = true)
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"获取成功\", "
                                                    + "\"data\": {\"route\": {}}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "航线不存在",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 404, "
                                                    + "\"errorCode\": \"ROUTE_NOT_FOUND\", "
                                                    + "\"message\": \"航线不存在\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录或 token 过期",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 401, "
                                                    + "\"errorCode\": \"UNAUTHORIZED\", "
                                                    + "\"message\": \"Missing token\", \"data\": null}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/detail")
    public Result<Map<String, Object>> getRouteDetail(@RequestParam String routeNum) {
        String userName = currentUserName();
        log.info("用户: {} 查询航线详情，编号: {}", maskedName(), routeNum);
        try {
            Route route = routeService.getRouteByRouteNum(routeNum, userName);

            Map<String, Object> result = new HashMap<>();
            result.put("route", route);
            return Result.success("获取成功", result);
        } catch (BusinessException e) {
            log.warn("航线详情查询失败，用户: {}, 编号: {}, 原因: {}",
                    maskedName(), routeNum, e.getMessage());
            throw e;
        }
    }

    @Operation(
            summary = "删除航线",
            description = "删除指定ID的航线，只能删除当前用户创建的航线",
            parameters = {
                    @Parameter(name = "id", description = "航线数据库ID", required = true)
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "删除成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, "
                                                    + "\"message\": \"航线删除成功\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "无权删除",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 403, "
                                                    + "\"errorCode\": \"INVALID_PARAM\", "
                                                    + "\"message\": \"无权删除该航线\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "航线不存在",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 404, "
                                                    + "\"errorCode\": \"INVALID_PARAM\", "
                                                    + "\"message\": \"航线不存在\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录或 token 过期",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 401, "
                                                    + "\"errorCode\": \"UNAUTHORIZED\", "
                                                    + "\"message\": \"Missing token\", \"data\": null}"
                                    )
                            )
                    )
            }
    )
    @RateLimiter(limit = 5, windowSeconds = 60)
    @DeleteMapping("/delete")
    public Result<Map<String, Object>> deleteRoute(@RequestParam Long id) {
        String userName = currentUserName();
        log.info("用户: {} 尝试删除航线，ID: {}", maskedName(), id);
        try {
            routeService.deleteRoute(id, userName);
            log.info("航线删除成功，ID: {}", id);
            return Result.success("航线删除成功");
        } catch (BusinessException e) {
            log.warn("航线删除失败，用户: {}, ID: {}, 原因: {}", maskedName(), id, e.getMessage());
            throw e;
        }
    }

    @Operation(
            summary = "执行航线",
            description = "执行指定编号的航线，通过 websocket 发送命令给无人机",
            parameters = {
                    @Parameter(name = "routeNum", description = "航线业务编号", required = true)
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "执行成功，无人机已确认",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, "
                                                    + "\"message\": \"无人机已确认执行指令\", "
                                                    + "\"data\": {\"requestId\": \"uuid\", \"timedOut\": false}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "408",
                            description = "设备确认超时",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 408, "
                                                    + "\"errorCode\": \"LIVE_ACK_TIMEOUT\", "
                                                    + "\"message\": \"指令已发送但无人机未确认，请检查设备状态\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "无人机未连接",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 400, "
                                                    + "\"errorCode\": \"UAV_NOT_CONNECTED\", "
                                                    + "\"message\": \"无人机未建立 WebSocket 连接\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录或 token 过期",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 401, "
                                                    + "\"errorCode\": \"UNAUTHORIZED\", "
                                                    + "\"message\": \"Missing token\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "无权执行此航线",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 403, "
                                                    + "\"errorCode\": \"ROUTE_NOT_FOUND\", "
                                                    + "\"message\": \"无权执行此航线\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "429",
                            description = "触发限流",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 429, "
                                                    + "\"errorCode\": \"RATE_LIMITED\", "
                                                    + "\"message\": \"请求过于频繁，请稍后重试\", \"data\": null}"
                                    )
                            )
                    )
            }
    )
    @RateLimiter(limit = 5, windowSeconds = 60)
    @PostMapping("/start")
    public Result<Map<String, Object>> assignRouteToUav(@RequestParam String routeNum) {
        String userName = currentUserName();
        log.info("用户: {} 尝试执行航线，编号: {}", maskedName(), routeNum);
        try {
            WsCommandAckResult ack = routeService.assignRouteToUav(routeNum, userName);

            Map<String, Object> response = new HashMap<>();
            response.put("requestId", ack.getRequestId());
            response.put("timedOut", ack.isTimedOut());

            if (ack.isSuccess()) {
                log.info("航线执行成功，编号: {}", routeNum);
                return Result.success("无人机已确认执行指令", response);
            }

            if (ack.isTimedOut()) {
                log.warn("航线执行超时，编号: {}", routeNum);
                return Result.fail(408, ack.getCode(), "指令已发送但无人机未确认，请检查设备状态");
            }

            log.warn("航线执行失败，编号: {}, 原因: {}", routeNum, ack.getMessage());
            return Result.fail(400, ack.getCode(), ack.getMessage());
        } catch (BusinessException e) {
            log.warn("航线执行失败，用户: {}, 编号: {}, 原因: {}", maskedName(), routeNum, e.getMessage());
            throw e;
        }
    }

    private String currentUserName() {
        return UserContext.getUsername();
    }

    private String maskedName() {
        return LogMaskUtil.maskUserName(currentUserName());
    }
}

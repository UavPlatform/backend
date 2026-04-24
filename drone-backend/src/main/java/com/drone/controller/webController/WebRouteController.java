package com.drone.controller.webController;

import com.drone.pojo.dto.RouteDto;
import com.drone.pojo.entity.Route;
import com.drone.pojo.result.Result;
import com.drone.server.config.AmapConfig;
import com.drone.server.exception.BusinessException;
import com.drone.server.exception.UnauthorizedException;
import com.drone.server.util.UserContext;
import com.drone.service.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
                                            example = "{\"success\": true, \"key\": \"your_amap_key\", \"securityKey\": \"your_security_key\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/init")
    public Result<Map<String, Object>> init() {
        log.info("初始化所在地地图");
        Map<String, Object> result = new HashMap<>();
        result.put("key", amapConfig.getKey());
        result.put("securityKey", amapConfig.getSecurityKey());
        log.info("获取key成功");
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
                                            example = "{\"success\": true, \"message\": \"航线保存成功\"}"
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
                                            example = "{\"success\": false, \"message\": \"航线名称不能为空\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"当前用户未登录\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/save")
    public Result<Map<String, Object>> saveRoute(@RequestBody RouteDto routeDto) {
        String userName = UserContext.getUsername();
        if (userName == null || userName.isBlank()) {
            throw new UnauthorizedException("当前用户未登录");
        }

        Route saved = routeService.saveRoute(routeDto, userName);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "航线保存成功");
        result.put("id", saved.getId());
        log.info("用户 {} 保存航线 {} 成功，航线ID: {}", userName, routeDto.getRouteName(), saved.getId());
        return Result.success(result);
    }

    @Operation(
            summary = "获取当前用户的航线列表",
            description = "获取当前登录用户创建的所有航线",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"routes\": [], \"message\": \"获取成功\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"当前用户未登录\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/list")
    public Result<Map<String, Object>> listRoutes() {
        String userName = UserContext.getUsername();
        if (userName == null || userName.isBlank()) {
            throw new UnauthorizedException("当前用户未登录");
        }

        List<Route> routes = routeService.getRoutesByUser(userName);

        Map<String, Object> result = new HashMap<>();
        result.put("routes", routes);
        result.put("message", "获取成功");
        return Result.success(result);
    }

    @Operation(
            summary = "根据无人机ID获取航线列表",
            description = "获取指定无人机关联的航线列表",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"routes\": [], \"message\": \"获取成功\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/listByDjiId")
    public Result<Map<String, Object>> listRoutesByDjiId(@RequestParam String djiId) {
        List<Route> routes = routeService.getRoutesByDjiId(djiId);
        Map<String, Object> result = new HashMap<>();
        result.put("routes", routes);
        result.put("message", "获取成功");
        return Result.success(result);
    }

    @Operation(
            summary = "获取航线详情",
            description = "根据ID获取航线详细信息，包含航点列表",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"route\": {}}"
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
                                            example = "{\"success\": false, \"message\": \"航线不存在\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/detail")
    public Result<Map<String, Object>> getRouteDetail(@RequestParam String userName) {
        List<Route> routes = routeService.getRoutesByUser(userName);

        Map<String, Object> result = new HashMap<>();
        result.put("route", routes);
        result.put("message", "获取成功");
        return Result.success(result);
    }

    @Operation(
            summary = "删除航线",
            description = "删除指定ID的航线，只能删除当前用户创建的航线",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "删除成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"message\": \"航线删除成功\"}"
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
                                            example = "{\"success\": false, \"message\": \"无权删除该航线\"}"
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
                                            example = "{\"success\": false, \"message\": \"航线不存在\"}"
                                    )
                            )
                    )
            }
    )
    @DeleteMapping("/delete")
    public Result<Map<String, Object>> deleteRoute(@RequestParam Long id) {
        String userName = UserContext.getUsername();
        if (userName == null || userName.isBlank()) {
            throw new UnauthorizedException("当前用户未登录");
        }

        routeService.deleteRoute(id, userName);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "航线删除成功");
        return Result.success(result);
    }

    @Operation(
            summary = "执行航线",
            description = "执行指定ID的航线，通过websocket发送命令给无人机",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "执行成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"message\": \"航线执行命令发送成功\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"当前用户未登录\"}"
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
                                            example = "{\"success\": false, \"message\": \"航线不存在\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/start")
    public Result<Void> assignRouteToUav(@RequestParam Long id) throws Exception {
        String userName = UserContext.getUsername();

        if (userName == null || userName.isBlank()) {
            throw new UnauthorizedException("当前用户未登录");
        }

        log.info("用户 {} 执行航线任务，航线ID: {}", userName, id);

        boolean success = routeService.assignRouteToUav(id);

        if (!success) {
            throw new Exception("航线执行失败");
        }

        log.info("航线执行命令发送成功，用户：{}，航线ID：{}", userName, id);
        return Result.success("航线执行命令发送成功");
    }
}

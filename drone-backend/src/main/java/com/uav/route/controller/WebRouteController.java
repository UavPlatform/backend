package com.uav.route.controller;

import com.uav.route.pojo.dto.RouteDto;
import com.uav.route.pojo.entity.Route;
import com.uav.server.result.Result;
import com.uav.route.pojo.vo.AmapConfigVO;
import com.uav.route.pojo.vo.RouteExecuteVO;
import com.uav.route.pojo.vo.RouteListVO;
import com.uav.route.pojo.vo.RoutePageVO;
import com.uav.route.pojo.vo.RouteSaveVO;
import com.uav.route.pojo.vo.RouteVo;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.config.AmapConfig;
import com.uav.server.util.UserContext;
import com.uav.server.ws.handler.WsCommandAckResult;
import com.uav.route.service.RouteService;
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

import java.util.List;

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
    public Result<AmapConfigVO> init() {
        return Result.success(new AmapConfigVO(amapConfig.getKey(), amapConfig.getSecurityKey()));
    }

    @OperationLog("保存航线")
    @RateLimiter(limit = 10, windowSeconds = 60)
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
                                                    + "\"data\": {\"id\": 15, \"routeNum\": \"RT20260525143022zhan0001\"}}"
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
    @PostMapping("/save")
    public Result<RouteSaveVO> saveRoute(@RequestBody RouteDto routeDto) {
        String userName = UserContext.getUsername();
        Route saved = routeService.saveRoute(routeDto, userName);
        return Result.success("航线保存成功", new RouteSaveVO(saved.getId(), saved.getRouteNum()));
    }

    @OperationLog("查询航线列表")
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
    public Result<RoutePageVO> listRoutes(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        String userName = UserContext.getUsername();
        Page<Route> routePage = routeService.getRoutesByUser(userName, page, size);

        List<RouteVo> routes = routePage.getContent().stream()
                .map(RouteVo::from)
                .toList();

        RoutePageVO vo = new RoutePageVO();
        vo.setRoutes(routes);
        vo.setCurrentPage(routePage.getNumber());
        vo.setTotalPages(routePage.getTotalPages());
        vo.setTotalElements(routePage.getTotalElements());
        return Result.success("获取成功", vo);
    }

    @OperationLog("查询无人机航线列表")
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
    public Result<RouteListVO> listRoutesByDjiId(@RequestParam String djiId) {
        List<Route> routes = routeService.getRoutesByDjiId(djiId);
        RouteListVO vo = new RouteListVO();
        vo.setRoutes(routes.stream().map(RouteVo::from).toList());
        return Result.success("获取成功", vo);
    }

    @OperationLog("查询航线详情")
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
                                                    + "\"data\": {\"id\": 1, \"routeNum\": \"RTxxx\", \"routeName\": \"xxx\"}}"
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
    public Result<RouteVo> getRouteDetail(@RequestParam String routeNum) {
        String userName = UserContext.getUsername();
        Route route = routeService.getRouteByRouteNum(routeNum, userName);
        return Result.success("获取成功", RouteVo.from(route));
    }

    @OperationLog("删除航线")
    @RateLimiter(limit = 5, windowSeconds = 60)
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
    @DeleteMapping("/delete")
    public Result<Void> deleteRoute(@RequestParam Long id) {
        String userName = UserContext.getUsername();
        routeService.deleteRoute(id, userName);
        return Result.success("航线删除成功");
    }

    @OperationLog("执行航线")
    @RateLimiter(limit = 5, windowSeconds = 60)
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
    @PostMapping("/start")
    public Result<RouteExecuteVO> assignRouteToUav(@RequestParam String routeNum) {
        String userName = UserContext.getUsername();
        WsCommandAckResult ack = routeService.assignRouteToUav(routeNum, userName);

        RouteExecuteVO vo = new RouteExecuteVO(ack.getRequestId(), ack.isTimedOut());

        if (ack.isSuccess()) {
            return Result.success("无人机已确认执行指令", vo);
        }
        if (ack.isTimedOut()) {
            return Result.fail(408, ack.getCode(), "指令已发送但无人机未确认，请检查设备状态");
        }
        return Result.fail(400, ack.getCode(), ack.getMessage());
    }
}

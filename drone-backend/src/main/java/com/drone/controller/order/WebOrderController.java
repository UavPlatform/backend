package com.drone.controller.order;

import com.drone.pojo.entity.MissionOrder;
import com.drone.pojo.enums.ApiErrorCode;
import com.drone.pojo.result.Result;
import com.drone.server.annotation.RateLimiter;
import com.drone.server.exception.BusinessException;
import com.drone.server.util.LogMaskUtil;
import com.drone.server.util.UserContext;
import com.drone.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Order API", description = "订单接口")
@RestController
@RequestMapping("/order")
@Slf4j
public class WebOrderController {

    @Autowired
    private OrderService orderService;

    @Operation(
            summary = "创建订单",
            description = "根据指定的航线创建支付订单，需传入 routeNum",
            parameters = {
                    @Parameter(name = "routeNum", description = "航线业务编号，从航线列表/详情中获取", required = true)
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "创建成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"订单创建成功\", "
                                                    + "\"data\": {\"orderNum\": \"MO20260524143022123zhan0001\", "
                                                    + "\"totalAmount\": 12.50, \"distance\": 250.0, "
                                                    + "\"djiId\": \"DJI-001\", \"routeName\": \"测试航线\", \"orderStatus\": 0}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "航线不属于当前用户",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 403, "
                                                    + "\"errorCode\": \"ROUTE_NOT_FOUND\", "
                                                    + "\"message\": \"无权使用此航线\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "① 已有待支付订单 ② 航线不存在",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 400, "
                                                    + "\"errorCode\": \"ORDER_ALREADY_EXISTS\", "
                                                    + "\"message\": \"您已有待支付的订单，请先完成支付\", \"data\": null}"
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
    @RateLimiter(limit = 5, windowSeconds = 60)
    @PostMapping("/create")
    public Result<Map<String, Object>> createOrder(@RequestParam String routeNum) {
        if (routeNum == null || routeNum.isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "routeNum 不能为空");
        }
        String name = currentUserName();
        log.info("用户: {} 尝试创建订单，航线: {}", maskedName(), routeNum);
        try {
            MissionOrder order = orderService.createOrder(name, routeNum);
            log.info("订单创建成功，订单号: {}", order.getOrderNum());
            return Result.success("订单创建成功", toMap(order));
        } catch (BusinessException e) {
            log.warn("订单创建失败，用户: {}, 原因: {}", maskedName(), e.getMessage());
            throw e;
        }
    }

    @Operation(
            summary = "订单列表",
            description = "获取当前用户的所有订单，按创建时间倒序",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"获取成功\", "
                                                    + "\"data\": {\"orders\": [{"
                                                    + "\"orderNum\": \"MO20260524143022123zhan0001\", "
                                                    + "\"totalAmount\": 12.50, \"distance\": 250.0, "
                                                    + "\"djiId\": \"DJI-001\", \"routeName\": \"测试航线\", \"orderStatus\": 0}], "
                                                    + "\"message\": \"获取成功\"}}"
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
    public Result<Map<String, Object>> listOrders(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        String name = currentUserName();
        log.info("用户: {} 查询订单列表，第{}页/每页{}条", maskedName(), page, size);
        try {
            Page<MissionOrder> orderPage = orderService.listOrders(name, page, size);
            List<Map<String, Object>> orderList = new ArrayList<>();
            for (MissionOrder order : orderPage.getContent()) {
                orderList.add(toMap(order));
            }
            log.info("订单列表查询成功，用户: {}, 共 {} 条", maskedName(), orderList.size());

            Map<String, Object> result = new HashMap<>();
            result.put("orders", orderList);
            result.put("currentPage", orderPage.getNumber());
            result.put("totalPages", orderPage.getTotalPages());
            result.put("totalElements", orderPage.getTotalElements());
            result.put("message", "获取成功");
            return Result.success("获取成功", result);
        } catch (BusinessException e) {
            log.warn("订单列表查询失败，用户: {}, 原因: {}", maskedName(), e.getMessage());
            throw e;
        }
    }

    @Operation(
            summary = "订单详情",
            description = "根据订单号获取订单详细信息",
            parameters = {
                    @Parameter(name = "orderNum", description = "订单号", required = true)
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
                                                    + "\"data\": {\"orderNum\": \"MO20260524143022123zhan0001\", "
                                                    + "\"totalAmount\": 12.50, \"distance\": 250.0, "
                                                    + "\"djiId\": \"DJI-001\", \"routeName\": \"测试航线\", \"orderStatus\": 0}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "订单不存在",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 404, "
                                                    + "\"errorCode\": \"ORDER_NOT_FOUND\", "
                                                    + "\"message\": \"订单不存在\", \"data\": null}"
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
    public Result<Map<String, Object>> getOrderDetail(@RequestParam String orderNum) {
        if (orderNum == null || orderNum.isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    com.drone.pojo.enums.ApiErrorCode.INVALID_PARAM, "orderNum 不能为空");
        }
        String name = currentUserName();
        log.info("用户: {} 查询订单详情，订单号: {}", maskedName(), orderNum);
        try {
            MissionOrder order = orderService.getOrderDetail(orderNum, name);
            return Result.success("获取成功", toMap(order));
        } catch (BusinessException e) {
            log.warn("订单详情查询失败，用户: {}, 订单号: {}, 原因: {}",
                    maskedName(), orderNum, e.getMessage());
            throw e;
        }
    }

    @Operation(
            summary = "取消订单",
            description = "取消待支付状态的订单",
            parameters = {
                    @Parameter(name = "orderNum", description = "订单号", required = true)
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "取消成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, "
                                                    + "\"message\": \"订单取消成功\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "订单状态不允许取消（非待支付状态）",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 400, "
                                                    + "\"errorCode\": \"ORDER_STATUS_INVALID\", "
                                                    + "\"message\": \"仅待支付状态的订单可以取消，当前状态: 已支付\", \"data\": null}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "订单不存在",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 404, "
                                                    + "\"errorCode\": \"ORDER_NOT_FOUND\", "
                                                    + "\"message\": \"订单不存在\", \"data\": null}"
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
    @PostMapping("/cancel")
    public Result<Map<String, Object>> cancelOrder(@RequestParam String orderNum) {
        if (orderNum == null || orderNum.isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "orderNum 不能为空");
        }
        String name = currentUserName();
        log.info("用户: {} 尝试取消订单，订单号: {}", maskedName(), orderNum);
        try {
            orderService.cancelOrder(orderNum, name);
            log.info("订单取消成功，订单号: {}", orderNum);
            return Result.success("订单取消成功");
        } catch (BusinessException e) {
            log.warn("订单取消失败，用户: {}, 订单号: {}, 原因: {}",
                    maskedName(), orderNum, e.getMessage());
            throw e;
        }
    }

    private String currentUserName() {
        return UserContext.getUsername();
    }

    private String maskedName() {
        return LogMaskUtil.maskUserName(currentUserName());
    }

    private Map<String, Object> toMap(MissionOrder order) {
        Map<String, Object> map = new HashMap<>();
        map.put("orderNum", order.getOrderNum());
        map.put("totalAmount", order.getTotalAmount());
        map.put("distance", order.getTotalDistance());
        map.put("djiId", order.getDjiId());
        map.put("orderStatus", order.getOrderStatus().getCode());
        map.put("routeName", order.getRoute() != null ? order.getRoute().getRouteName() : null);
        return map;
    }
}

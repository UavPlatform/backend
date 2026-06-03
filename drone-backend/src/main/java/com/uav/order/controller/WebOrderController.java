package com.uav.order.controller;

import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.result.Result;
import com.uav.order.pojo.vo.OrderListVO;
import com.uav.order.pojo.vo.OrderVO;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Order API", description = "订单接口")
@RestController
@RequestMapping("/order")
@Slf4j
public class WebOrderController {

    @Autowired
    private OrderService orderService;

    @OperationLog("创建订单")
    @RateLimiter(limit = 5, windowSeconds = 60)
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
    @PostMapping("/create")
    public Result<OrderVO> createOrder(@RequestParam String routeNum) {
        if (routeNum == null || routeNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "routeNum 不能为空");
        }
        String name = UserContext.getUsername();
        MissionOrder order = orderService.createOrder(name, routeNum);
        return Result.success("订单创建成功", OrderVO.from(order));
    }

    @OperationLog("查询订单列表")
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
                                                    + "\"currentPage\": 0, \"totalPages\": 1, \"totalElements\": 1}}"
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
    public Result<OrderListVO> listOrders(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        String name = UserContext.getUsername();
        Page<MissionOrder> orderPage = orderService.listOrders(name, page, size);

        List<OrderVO> orderList = orderPage.getContent().stream()
                .map(OrderVO::from)
                .toList();

        OrderListVO vo = new OrderListVO();
        vo.setOrders(orderList);
        vo.setCurrentPage(orderPage.getNumber());
        vo.setTotalPages(orderPage.getTotalPages());
        vo.setTotalElements(orderPage.getTotalElements());
        return Result.success("获取成功", vo);
    }

    @OperationLog("查询订单详情")
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
    public Result<OrderVO> getOrderDetail(@RequestParam String orderNum) {
        if (orderNum == null || orderNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "orderNum 不能为空");
        }
        String name = UserContext.getUsername();
        MissionOrder order = orderService.getOrderDetail(orderNum, name);
        return Result.success("获取成功", OrderVO.from(order));
    }

    @OperationLog("取消订单")
    @RateLimiter(limit = 3, windowSeconds = 60)
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
    public Result<Void> cancelOrder(@RequestParam String orderNum) {
        if (orderNum == null || orderNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "orderNum 不能为空");
        }
        String name = UserContext.getUsername();
        orderService.cancelOrder(orderNum, name);
        return Result.success("订单取消成功");
    }
}

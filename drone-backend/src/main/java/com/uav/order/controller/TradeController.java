package com.uav.order.controller;

import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.vo.OrderListVO;
import com.uav.order.pojo.vo.OrderVO;
import com.uav.order.pojo.vo.TradeStatsVO;
import com.uav.order.service.OrderTradeService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Trade API", description = "我的交易")
@RestController
@RequestMapping("/order")
@Slf4j
@RequiredArgsConstructor
public class TradeController {

    private final OrderTradeService orderTradeService;

    @OperationLog("查询我的交易统计")
    @Operation(summary = "我的交易统计",
            description = "App 个人中心各入口的计数：累计订单数、已完成交易数、我发布的、我买到的、我卖出的、待评价。"
                    + "累计订单与我买到的按买家视角，我卖出的按被选定应征的飞手视角；"
                    + "已完成交易数仅统计订单状态为「已完成」的订单（待验收/争议中/已退款均不计）")
    @GetMapping("/stats")
    public Result<TradeStatsVO> getTradeStats() {
        Long userId = UserContext.getUserId();
        return Result.success("获取成功", orderTradeService.getTradeStats(userId));
    }

    @OperationLog("查询我的交易")
    @Operation(summary = "我的交易",
            description = "我买到的与我卖出的合并列表，按创建时间倒序",
            parameters = {
                    @Parameter(name = "status", description = "订单状态过滤（枚举名如 COMPLETED，或状态码如 4），不传为全部"),
                    @Parameter(name = "page", description = "页码，从 0 开始"),
                    @Parameter(name = "size", description = "每页条数")
            })
    @GetMapping("/trades")
    public Result<OrderListVO> listMyTrades(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String status) {
        Long userId = UserContext.getUserId();
        Page<MissionOrder> orderPage = orderTradeService.listMyTrades(userId, page, size, status);
        return Result.success("获取成功", toListVO(orderPage));
    }

    @OperationLog("查询我卖出的订单")
    @Operation(summary = "我卖出的",
            description = "被用户选定的应征属于我的订单（成交指针 selected_application_id），按创建时间倒序",
            parameters = {
                    @Parameter(name = "status", description = "订单状态过滤（枚举名如 COMPLETED，或状态码如 4），不传为全部"),
                    @Parameter(name = "page", description = "页码，从 0 开始"),
                    @Parameter(name = "size", description = "每页条数")
            })
    @GetMapping("/sold")
    public Result<OrderListVO> listSoldOrders(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String status) {
        Long userId = UserContext.getUserId();
        Page<MissionOrder> orderPage = orderTradeService.listSoldOrders(userId, page, size, status);
        return Result.success("获取成功", toListVO(orderPage));
    }

    @OperationLog("查询待评价订单")
    @Operation(summary = "待评价",
            description = "我已完成的、尚未提交评价的订单，按创建时间倒序",
            parameters = {
                    @Parameter(name = "page", description = "页码，从 0 开始"),
                    @Parameter(name = "size", description = "每页条数")
            })
    @GetMapping("/pending-review")
    public Result<OrderListVO> listPendingReviewOrders(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getUserId();
        Page<MissionOrder> orderPage = orderTradeService.listPendingReviewOrders(userId, page, size);
        return Result.success("获取成功", toListVO(orderPage));
    }

    /** 订单分页 → OrderListVO（与 OrderController#listOrders 的装配口径一致）。 */
    private OrderListVO toListVO(Page<MissionOrder> orderPage) {
        List<OrderVO> orderList = orderPage.getContent().stream()
                .map(OrderVO::from)
                .toList();

        OrderListVO vo = new OrderListVO();
        vo.setOrders(orderList);
        vo.setCurrentPage(orderPage.getNumber());
        vo.setTotalPages(orderPage.getTotalPages());
        vo.setTotalElements(orderPage.getTotalElements());
        return vo;
    }
}

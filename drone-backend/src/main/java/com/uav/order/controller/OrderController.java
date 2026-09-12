package com.uav.order.controller;

import com.uav.order.pojo.dto.CreateOrderDTO;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.service.OrderReviewService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.result.Result;
import com.uav.order.pojo.vo.OrderListVO;
import com.uav.order.pojo.vo.OrderVO;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.order.service.OrderService;
import com.uav.upload.entity.UploadedFile;
import com.uav.upload.service.UploadRecordService;
import com.uav.upload.vo.UploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private UploadRecordService uploadRecordService;

    @Autowired
    private OrderReviewService orderReviewService;
    private com.uav.live.service.impl.LiveDeviceResolver liveDeviceResolver;

    @OperationLog("查询订单列表")
    @Operation(summary = "订单列表", description = "获取当前用户的所有订单，按创建时间倒序")
    @GetMapping("/list")
    public Result<OrderListVO> listOrders(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getUserId();
        Page<MissionOrder> orderPage = orderService.listOrders(userId, page, size);

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
    @Operation(summary = "订单详情", description = "根据订单号获取详细信息（含交付文件列表）",
            parameters = {@Parameter(name = "orderNum", description = "订单号", required = true)})
    @GetMapping("/detail")
    public Result<OrderVO> getOrderDetail(@RequestParam String orderNum) {
        if (orderNum == null || orderNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "orderNum 不能为空");
        }
        Long userId = UserContext.getUserId();
        MissionOrder order = orderService.getOrderDetail(orderNum, userId);
        // 1B-4b：任务→设备映射（deviceId/liveState），用户端据此点亮「观看直播」入口
        var liveDevice = liveDeviceResolver.resolveForTask(
                order.getTask() != null ? order.getTask().getId() : null);
        OrderVO vo = OrderVO.from(order, liveDevice.deviceId(), liveDevice.liveState());

        // 查关联的交付文件（并行功能线：订单交付文件）
        List<UploadedFile> files = uploadRecordService.listByOrder(orderNum, 0, 100).getContent();
        vo.setFiles(files.stream().map(UploadVO::from).toList());

        // 是否已评价（并行功能线）
        vo.setHasReview(orderReviewService.hasReview(orderNum));

        return Result.success("获取成功", vo);
    }

    @OperationLog("取消订单")
    @RateLimiter(limit = 3, windowSeconds = 60)
    @Operation(summary = "取消订单", description = "取消待支付状态的订单",
            parameters = {@Parameter(name = "orderNum", description = "订单号", required = true)})
    @PostMapping("/cancel")
    public Result<Void> cancelOrder(@RequestParam String orderNum) {
        if (orderNum == null || orderNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "orderNum 不能为空");
        }
        Long userId = UserContext.getUserId();
        orderService.cancelOrder(orderNum, userId);
        return Result.success("订单取消成功");
    }

    @OperationLog("争议订单")
    @RateLimiter(limit = 3, windowSeconds = 60)
    @Operation(summary = "不满意交付结果", description = "将待确认的订单标记为争议中",
            parameters = {@Parameter(name = "orderNum", description = "订单号", required = true)})
    @PostMapping("/dispute")
    public Result<Void> disputeOrder(@RequestParam String orderNum) {
        if (orderNum == null || orderNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "orderNum 不能为空");
        }
        Long userId = UserContext.getUserId();
        orderService.disputeOrder(orderNum, userId);
        return Result.success("已标记为争议，等待处理");
    }
}

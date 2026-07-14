package com.uav.order.controller;

import com.uav.order.mapper.OrderReviewRepository;
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
    private OrderReviewRepository orderReviewRepository;

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
        OrderVO vo = OrderVO.from(order);

        // 查关联的交付文件
        List<UploadedFile> files = uploadRecordService.listByOrder(orderNum, 0, 100).getContent();
        vo.setFiles(files.stream().map(UploadVO::from).toList());

        // 是否已评价
        vo.setHasReview(orderReviewRepository.existsByOrderNum(orderNum));

        return Result.success("获取成功", vo);
    }

    @OperationLog("创建订单")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "创建订单", description = "根据任务编号创建飞行订单",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @PostMapping("/create")
    public Result<OrderVO> createOrder(@RequestParam String taskNum) {
        if (taskNum == null || taskNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "taskNum 不能为空");
        }
        Long userId = UserContext.getUserId();
        MissionOrder order = orderService.createOrder(userId, taskNum);
        return Result.success("订单创建成功", OrderVO.from(order));
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

package com.uav.order.controller;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.mapper.OrderReviewRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.entity.OrderReview;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Review API", description = "订单评价")
@RestController
@RequestMapping("/review")
@Slf4j
@RequiredArgsConstructor
public class ReviewController {

    private final OrderReviewRepository reviewRepository;
    private final OrderRepository orderRepository;

    @OperationLog("提交评价")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "提交评价", description = "对已完成的订单进行评分和评价，一个订单只能评价一次")
    @PostMapping("/submit")
    public Result<Map<String, Object>> submitReview(@RequestParam String orderNum,
                                                     @RequestParam int rating,
                                                     @RequestParam(required = false) String content) {
        if (rating < 1 || rating > 5) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "评分需在 1-5 之间");
        }
        Long userId = UserContext.getUserId();

        // 校验订单
        MissionOrder order = orderRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getOrderStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "仅已完成订单可评价，当前状态: " + order.getOrderStatus().getDesc());
        }
        if (reviewRepository.existsByOrderNum(orderNum)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "该订单已评价，不可重复提交");
        }

        OrderReview review = new OrderReview();
        review.setOrderNum(orderNum);
        review.setUserId(userId);
        review.setRating(rating);
        review.setContent(content);
        reviewRepository.save(review);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNum", orderNum);
        result.put("rating", rating);
        log.info("用户 {} 评价订单 {}，评分: {}", userId, orderNum, rating);
        return Result.success("评价成功", result);
    }

    @Operation(summary = "查看订单评价",
            parameters = {@Parameter(name = "orderNum", description = "订单号", required = true)})
    @GetMapping("/{orderNum}")
    public Result<OrderReview> getReview(@PathVariable String orderNum) {
        OrderReview review = reviewRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "该订单暂无评价"));
        return Result.success(review);
    }

    @Operation(summary = "我的评价列表")
    @GetMapping("/my")
    public Result<Map<String, Object>> myReviews(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getUserId();
        Page<OrderReview> reviewPage = reviewRepository
                .findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviews", reviewPage.getContent());
        result.put("currentPage", reviewPage.getNumber());
        result.put("totalPages", reviewPage.getTotalPages());
        result.put("totalElements", reviewPage.getTotalElements());
        return Result.success(result);
    }
}

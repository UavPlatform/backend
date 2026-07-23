package com.uav.order.controller;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollUtil;
import com.uav.order.pojo.entity.OrderReview;
import com.uav.order.pojo.vo.ReviewListVO;
import com.uav.order.pojo.vo.SubmitReviewVO;
import com.uav.order.service.OrderReviewService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Review API", description = "订单评价")
@RestController
@RequestMapping("/review")
@Slf4j
@RequiredArgsConstructor
public class ReviewController {

    private final OrderReviewService reviewService;

    @OperationLog("提交评价")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "提交评价", description = "对已完成的订单进行评分和评价，一个订单只能评价一次")
    @PostMapping("/submit")
    public Result<SubmitReviewVO> submitReview(@RequestParam String orderNum,
                                                @RequestParam int rating,
                                                @RequestParam(required = false) String content) {
        Long userId = UserContext.getUserId();
        OrderReview review = reviewService.submitReview(orderNum, userId, rating, content);
        SubmitReviewVO vo = new SubmitReviewVO(review.getOrderNum(), review.getRating());
        return Result.success("评价成功", vo);
    }

    @Operation(summary = "查看订单评价",
            parameters = {@Parameter(name = "orderNum", description = "订单号", required = true)})
    @GetMapping("/{orderNum}")
    public Result<OrderReview> getReview(@PathVariable String orderNum) {
        OrderReview review = reviewService.getReview(orderNum);
        return Result.success(review);
    }

    @Operation(summary = "我的评价列表")
    @GetMapping("/my")
    public Result<ReviewListVO> myReviews(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getUserId();
        Page<OrderReview> reviewPage = reviewService.listMyReviews(userId, page, size);

        ReviewListVO vo = new ReviewListVO();
        vo.setReviews(reviewPage.getContent());
        vo.setCurrentPage(reviewPage.getNumber());
        vo.setTotalPages(reviewPage.getTotalPages());
        vo.setTotalElements(reviewPage.getTotalElements());
        return Result.success(vo);
    }
}

package com.uav.order.service.impl;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.mapper.OrderReviewRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.entity.OrderReview;
import com.uav.order.service.OrderReviewService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderReviewServiceImpl implements OrderReviewService {

    private final OrderReviewRepository reviewRepository;
    private final OrderRepository orderRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderReview submitReview(String orderNum, Long userId, int rating, String content) {
        if (rating < 1 || rating > 5) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "评分需在 1-5 之间");
        }

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

        log.info("用户 {} 评价订单 {}，评分: {}", userId, orderNum, rating);
        return review;
    }

    @Override
    public OrderReview getReview(String orderNum) {
        return reviewRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "该订单暂无评价"));
    }

    @Override
    public Page<OrderReview> listMyReviews(Long userId, int page, int size) {
        return reviewRepository.findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));
    }

    @Override
    public boolean hasReview(String orderNum) {
        return reviewRepository.existsByOrderNum(orderNum);
    }
}

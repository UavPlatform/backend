package com.uav.order.service;

import com.uav.order.pojo.entity.OrderReview;
import org.springframework.data.domain.Page;

public interface OrderReviewService {

    OrderReview submitReview(String orderNum, Long userId, int rating, String content);

    OrderReview getReview(String orderNum);

    Page<OrderReview> listMyReviews(Long userId, int page, int size);

    boolean hasReview(String orderNum);
}

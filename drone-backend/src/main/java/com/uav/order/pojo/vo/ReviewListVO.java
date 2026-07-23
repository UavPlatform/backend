package com.uav.order.pojo.vo;

import com.uav.order.pojo.entity.OrderReview;
import lombok.Data;

import java.util.List;

@Data
public class ReviewListVO {
    private List<OrderReview> reviews;
    private int currentPage;
    private int totalPages;
    private long totalElements;
}

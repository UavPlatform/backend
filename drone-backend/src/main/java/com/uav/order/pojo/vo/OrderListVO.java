package com.uav.order.pojo.vo;

import lombok.Data;

import java.util.List;

@Data
public class OrderListVO {
    private List<OrderVO> orders;
    private int currentPage;
    private int totalPages;
    private long totalElements;
}

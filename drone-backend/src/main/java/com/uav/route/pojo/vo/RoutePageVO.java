package com.uav.route.pojo.vo;

import lombok.Data;

import java.util.List;

@Data
public class RoutePageVO {
    private List<RouteVo> routes;
    private int currentPage;
    private int totalPages;
    private long totalElements;
}

package com.uav.feed.pojo.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FeedPageVO {
    private List<FeedVO> feeds;
    private int currentPage;
    private int totalPages;
    private long totalElements;
}

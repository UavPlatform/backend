package com.uav.feed.pojo.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CommentPageVO {
    private List<FeedCommentVO> comments;
    private int currentPage;
    private int totalPages;
    private long totalElements;
}

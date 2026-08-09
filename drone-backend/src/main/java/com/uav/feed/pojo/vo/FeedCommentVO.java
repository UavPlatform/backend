package com.uav.feed.pojo.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FeedCommentVO {
    private Long id;
    private Long userId;
    private String userName;
    private String content;
    private Long parentId;
    private LocalDateTime createTime;
}

package com.uav.feed.pojo.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class FeedVO {
    private Long id;
    private Long userId;
    private String userName;
    private String content;
    private List<MediaEntry> media;
    private long likeCount;
    private long commentCount;
    private boolean liked;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @Data
    @Builder
    public static class MediaEntry {
        private Long id;
        private String url;
        private String type;
    }
}

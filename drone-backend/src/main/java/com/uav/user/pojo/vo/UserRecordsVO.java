package com.uav.user.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserRecordsVO {
    private List<RecordItem> records;
    private long total;
    private int totalPages;

    @Data
    public static class RecordItem {
        private Long id;
        private String djiId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}

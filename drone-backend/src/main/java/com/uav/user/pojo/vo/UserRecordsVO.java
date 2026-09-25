package com.uav.user.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "用户观看记录分页结果")
public class UserRecordsVO {
    @Schema(description = "当前页观看记录列表")
    private List<RecordItem> records;
    @Schema(description = "观看记录总条数")
    private long total;
    @Schema(description = "总页数")
    private int totalPages;

    @Data
    @Schema(description = "单条观看记录")
    public static class RecordItem {
        @Schema(description = "记录ID")
        private Long id;
        @Schema(description = "设备ID(DJI ID)")
        private String djiId;
        @Schema(description = "观看开始时间（ISO-8601，如 2026-05-31T10:00:00）")
        private LocalDateTime startTime;
        @Schema(description = "观看结束时间（ISO-8601；未结束时为 null）")
        private LocalDateTime endTime;
    }
}

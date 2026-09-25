package com.uav.task.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.server.enums.TaskType;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskDto {
    private String taskName;
    private TaskType type;
    private String description;
    /** 协商价（可选，留空 = 按平台参考价） */
    private Double reward;
    /** 货物重量（kg，可选，仅按重量计费的类型参与计费） */
    private Double weight;

    /**
     * 任务期望执行时间（APP P0-4 契约修复）。可选；线上格式 "yyyy-MM-dd HH:mm:ss"
     * （Flutter 发布页拼接 date.toIso8601String().split('T')[0] + ' ' + HH:mm:00）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime taskTime;

    private List<WaypointDto> waypoints;
}

package com.uav.task.pojo.dto;

import com.uav.server.enums.TaskType;
import lombok.Data;
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
    /** 计划执行时间，'yyyy-MM-dd HH:mm:ss'（可选，用于夜间附加费判断） */
    private String taskTime;
    private List<WaypointDto> waypoints;
}

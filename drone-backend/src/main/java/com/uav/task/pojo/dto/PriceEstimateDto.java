package com.uav.task.pojo.dto;

import com.uav.server.enums.TaskType;
import lombok.Data;

import java.util.List;

/** 计价请求：与发布任务同口径，预览参考价用 */
@Data
public class PriceEstimateDto {
    private TaskType taskType;
    private List<WaypointDto> waypoints;
    private Double weight;
    /** 计划执行时间，'yyyy-MM-dd HH:mm:ss'，用于夜间附加费判断（可空） */
    private String taskTime;
}

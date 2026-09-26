package com.uav.task.pojo.dto;

import com.uav.server.enums.TaskType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 计价请求：与发布任务同口径，预览参考价用 */
@Data
public class PriceEstimateDto {
    private TaskType taskType;
    private List<WaypointDto> waypoints;
    /** 吊运货物重量（kg），与 {@code TaskDto.cargoWeightKg} 同口径 */
    private BigDecimal weight;
    /** 计划执行时间，'yyyy-MM-dd HH:mm:ss'，用于夜间附加费判断（可空） */
    private String taskTime;
}

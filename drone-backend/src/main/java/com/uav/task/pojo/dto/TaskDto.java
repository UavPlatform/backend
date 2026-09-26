package com.uav.task.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.server.enums.CargoCategory;
import com.uav.server.enums.TaskType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskDto {
    private String taskName;
    private TaskType type;
    private String description;

    /**
     * 任务期望执行时间（APP P0-4 契约修复）。可选；线上格式 "yyyy-MM-dd HH:mm:ss"
     * （Flutter 发布页拼接 date.toIso8601String().split('T')[0] + ' ' + HH:mm:00）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime taskTime;

    private Double reward;

    /**
     * 吊运货物重量（kg），ADR-0003 报价公式因子；非吊运任务可不填。
     * 吊运应征（POST /rider/apply）时必须已填写，否则拒绝计价。
     */
    private BigDecimal cargoWeightKg;

    /** 吊运货物类别（CONSTRUCTION/EQUIPMENT/AGRICULTURAL，与计价配置表对齐）；不填按未知类别计费。 */
    private CargoCategory cargoCategory;

    private List<WaypointDto> waypoints;
}

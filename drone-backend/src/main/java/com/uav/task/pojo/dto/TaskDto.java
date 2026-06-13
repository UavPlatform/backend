package com.uav.task.pojo.dto;

import com.uav.server.enums.TaskType;
import lombok.Data;
import java.util.List;

@Data
public class TaskDto {
    private String taskName;
    private TaskType type;
    private String description;
    private List<WaypointDto> waypoints;
}

package com.uav.task.pojo.vo;

import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.server.enums.TaskStatus;
import com.uav.server.enums.TaskType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskVo {
    private Long id;
    private String taskNum;
    private String taskName;
    private Long userId;
    private TaskType taskType;
    private TaskStatus taskStatus;
    private String description;
    private Long riderId;
    private String riderName;
    private LocalDateTime acceptTime;
    private String orderNum;
    private BigDecimal totalAmount;
    private BigDecimal totalDistance;
    private String orderStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<WaypointVo> waypoints;

    public static TaskVo from(Task task) {
        return from(task, null);
    }

    public static TaskVo from(Task task, TaskAssignment assignment) {
        TaskVo vo = new TaskVo();
        vo.setId(task.getId());
        vo.setTaskNum(task.getTaskNum());
        vo.setTaskName(task.getTaskName());
        vo.setUserId(task.getUserId());
        vo.setTaskType(task.getTaskType());
        vo.setTaskStatus(task.getTaskStatus());
        vo.setDescription(task.getDescription());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        if (assignment != null) {
            vo.setRiderId(assignment.getRiderId());
            vo.setAcceptTime(assignment.getAcceptTime());
        }
        if (task.getWaypoints() != null) {
            vo.setWaypoints(task.getWaypoints().stream()
                    .map(WaypointVo::from)
                    .toList());
        }
        return vo;
    }
}

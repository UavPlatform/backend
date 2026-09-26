package com.uav.task.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.server.enums.MatchStatus;
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

    /**
     * 撮合子状态（TASK-BACKEND-004 / ADR-0003 决定 6）：SEEKING_RIDER → NEGOTIATING →
     * AWAITING_PAYMENT → AWAITING_RIDER_CONFIRM → CONFIRMED → PENDING_ACCEPTANCE → CLOSED。
     */
    private MatchStatus matchStatus;

    /** 选定应征的系统报价（= 成交价 totalAmount，严格相等；未选定时为 null） */
    private BigDecimal quotedAmount;

    /** 选定应征的机型（用户下单后可见成交机型；未选定时为 null） */
    private Long aircraftModelId;

    /** 机型显示名（如 DJI FlyCart 30） */
    private String aircraftModelName;

    /** 机型型号编码（如 FC30、M350RTK） */
    private String modelCode;

    /** 约定作业时间（ADR-0003 决定 4，下单时由用户提交） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime scheduledTime;

    /** 用户下单确认时刻（ADR-0003 决定 4） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime userConfirmedAt;

    /** 飞手确认接单与约定时间的时刻（ADR-0003 决定 4） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime riderConfirmedAt;

    /** 吊运货物重量 kg（TASK-BACKEND-003 采集，TASK-BACKEND-004 回显给发单/详情页） */
    private BigDecimal cargoWeightKg;

    /** 吊运货物类别（CONSTRUCTION/EQUIPMENT/AGRICULTURAL） */
    private com.uav.server.enums.CargoCategory cargoCategory;

    /** 任务期望执行时间（1A-7a）：与发布侧一致的 "yyyy-MM-dd HH:mm:ss" 格式 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime taskTime;

    /** 飞手完成说明（1B-9a，来自 task_assignment.complete_note，可空） */
    private String completeNote;

    /** 操作提示（1B-9a 状态矩阵）：按 任务状态×订单状态×撮合状态 计算的人类可读提示，客户端可直接渲染 */
    private String actionHint;

    /** 任务关联的作业设备（1B-4b 微任务）：接单飞手绑定且在线的设备；无在线设备为 null */
    private String deviceId;

    /** 该设备的直播状态（IDLE/STARTING/RUNNING）；deviceId 为 null 时为 IDLE */
    private String liveState;

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
        vo.setMatchStatus(task.getMatchStatus());
        vo.setDescription(task.getDescription());
        vo.setTaskTime(task.getTaskTime());
        vo.setCargoWeightKg(task.getCargoWeightKg());
        vo.setCargoCategory(task.getCargoCategory());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        if (assignment != null) {
            vo.setRiderId(assignment.getRiderId());
            vo.setAcceptTime(assignment.getAcceptTime());
            vo.setCompleteNote(assignment.getCompleteNote());
        }
        if (task.getWaypoints() != null) {
            vo.setWaypoints(task.getWaypoints().stream()
                    .map(WaypointVo::from)
                    .toList());
        }
        return vo;
    }
}

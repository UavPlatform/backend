package com.uav.task.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    /** 货物重量（kg，计费输入） */
    private Double weight;
    /** 平台参考价（起步价 + 里程费 + 重量阶梯费 + 夜间附加费） */
    private BigDecimal referencePrice;
    /** 计费明细 JSON（PriceDetailVO 序列化） */
    private String priceDetail;
    /** 超重等场景：需平台人工报价 */
    private Boolean needManualQuote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 任务期望执行时间（1A-7a）：与发布侧一致的 "yyyy-MM-dd HH:mm:ss" 格式 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime taskTime;

    /** 飞手完成说明（1B-9a，来自 task_assignment.complete_note，可空） */
    private String completeNote;

    /** 操作提示（1B-9a 状态矩阵）：按 任务状态×订单状态 计算的人类可读提示，客户端可直接渲染 */
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
        vo.setDescription(task.getDescription());
        // 计费字段（账户计费模块）
        vo.setWeight(task.getWeight());
        vo.setReferencePrice(task.getReferencePrice());
        vo.setPriceDetail(task.getPriceDetail());
        vo.setNeedManualQuote(task.getNeedManualQuote());
        // 任务期望执行时间（1A-7a 契约，替代早期的 plannedTime）
        vo.setTaskTime(task.getTaskTime());
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

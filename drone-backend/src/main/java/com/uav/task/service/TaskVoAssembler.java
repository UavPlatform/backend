package com.uav.task.service;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.live.service.impl.LiveDeviceResolver;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.ApplicationStatus;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.vo.TaskActionHints;
import com.uav.task.pojo.vo.TaskVo;
import com.uav.user.mapper.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 任务详情视图装配（TASK-BACKEND-004）：任务 + 接单记录 + 订单 + 选定应征（报价/机型）
 * + 撮合状态提示 + 设备/直播映射，用户端与飞手端详情共用同一装配，避免两处漂移。
 */
@Component
public class TaskVoAssembler {

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TaskApplicationRepository taskApplicationRepository;

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LiveDeviceResolver liveDeviceResolver;

    public TaskVo assemble(Task task) {
        TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElse(null);
        TaskVo vo = TaskVo.from(task, assignment);
        if (assignment != null) {
            userRepository.findById(assignment.getRiderId())
                    .ifPresent(user -> vo.setRiderName(user.getUserName()));
        }

        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElse(null);
        if (order != null) {
            vo.setOrderNum(order.getOrderNum());
            vo.setTotalAmount(order.getTotalAmount());
            vo.setTotalDistance(order.getTotalDistance());
            vo.setOrderStatus(order.getOrderStatus().name());
            vo.setScheduledTime(order.getScheduledTime());
            vo.setUserConfirmedAt(order.getUserConfirmedAt());
            vo.setRiderConfirmedAt(order.getRiderConfirmedAt());
        }

        // 选定应征：成交价（= quotedAmount）与成交机型
        TaskApplication selected = order != null && order.getSelectedApplicationId() != null
                ? taskApplicationRepository.findById(order.getSelectedApplicationId()).orElse(null)
                : taskApplicationRepository.findByTaskIdAndStatus(task.getId(), ApplicationStatus.SELECTED)
                        .orElse(null);
        if (selected != null) {
            vo.setAircraftModelId(selected.getAircraftModelId());
            vo.setQuotedAmount(selected.getQuotedAmount());
            aircraftModelRepository.findById(selected.getAircraftModelId())
                    .ifPresent(model -> {
                        vo.setModelCode(model.getModelCode());
                        vo.setAircraftModelName(model.getDisplayName());
                    });
        }

        // 状态矩阵：任务状态 × 订单状态 × 撮合状态
        vo.setActionHint(TaskActionHints.hint(task.getTaskStatus(),
                order != null ? order.getOrderStatus() : null,
                task.getMatchStatus()));

        // 1B-4b：任务→设备映射（deviceId/liveState），用户端据此点亮「观看直播」入口
        var liveDevice = liveDeviceResolver.resolveForTask(task.getId());
        vo.setDeviceId(liveDevice.deviceId());
        vo.setLiveState(liveDevice.liveState());
        return vo;
    }
}

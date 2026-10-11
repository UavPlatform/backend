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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** 详情：用户端与飞手端共用（单条即批量装配的特例，两条路径不会漂移）。 */
    public TaskVo assemble(Task task) {
        return assembleAll(List.of(task), null).get(0);
    }

    /**
     * 飞手视角的列表批量装配（/rider/my-tasks、/rider/my-tasks/history）。
     *
     * <p>每类关联数据一次批量查询（接单记录、订单、应征、机型、飞手昵称、设备），
     * 查询数与列表长度无关，避免逐条 {@link #assemble} 的 N+1。
     *
     * <p>应征中/洽谈中阶段尚未选定飞手、也可能尚无订单：此时以<b>本人应征</b>的系统报价
     * （ADR-0003 平台计价，飞手不可改价）填充 {@code quotedAmount} 与机型，卡片据此显示价格而不是「面议」。
     */
    public List<TaskVo> assembleForRider(List<Task> tasks, Long riderId) {
        return assembleAll(tasks, riderId);
    }

    private List<TaskVo> assembleAll(List<Task> tasks, Long viewerRiderId) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<Long> taskIds = tasks.stream().map(Task::getId).toList();

        Map<Long, TaskAssignment> assignmentByTask = taskAssignmentRepository.findByTaskIdIn(taskIds).stream()
                .collect(Collectors.toMap(TaskAssignment::getTaskId, a -> a, (a, b) -> a));
        Map<Long, MissionOrder> orderByTask = orderRepository.findByTaskIdIn(taskIds).stream()
                .collect(Collectors.toMap(o -> o.getTask().getId(), o -> o, (a, b) -> a));

        // 选定应征：有订单以订单记录的 selectedApplicationId 为准，否则取 SELECTED 状态的应征
        Set<Long> selectedIds = orderByTask.values().stream()
                .map(MissionOrder::getSelectedApplicationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, TaskApplication> applicationById = new HashMap<>();
        taskApplicationRepository.findAllById(selectedIds).forEach(a -> applicationById.put(a.getId(), a));
        Map<Long, TaskApplication> selectedByTask = taskApplicationRepository
                .findByTaskIdInAndStatus(taskIds, ApplicationStatus.SELECTED).stream()
                .collect(Collectors.toMap(TaskApplication::getTaskId, a -> a, (a, b) -> a));
        Map<Long, TaskApplication> ownByTask = viewerRiderId == null ? Map.of()
                : taskApplicationRepository.findByTaskIdInAndRiderId(taskIds, viewerRiderId).stream()
                        .collect(Collectors.toMap(TaskApplication::getTaskId, a -> a, (a, b) -> a));

        Map<Long, TaskApplication> priceSourceByTask = new HashMap<>();
        for (Task task : tasks) {
            MissionOrder order = orderByTask.get(task.getId());
            TaskApplication source = order != null && order.getSelectedApplicationId() != null
                    ? applicationById.get(order.getSelectedApplicationId())
                    : selectedByTask.get(task.getId());
            if (source == null) {
                source = ownByTask.get(task.getId());
            }
            if (source != null) {
                priceSourceByTask.put(task.getId(), source);
            }
        }

        Map<Long, AircraftModel> modelById = new HashMap<>();
        aircraftModelRepository.findAllById(priceSourceByTask.values().stream()
                        .map(TaskApplication::getAircraftModelId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(m -> modelById.put(m.getId(), m));
        Map<Long, String> riderNameById = new HashMap<>();
        userRepository.findAllById(assignmentByTask.values().stream()
                        .map(TaskAssignment::getRiderId).collect(Collectors.toSet()))
                .forEach(u -> riderNameById.put(u.getId(), u.getUserName()));
        Map<Long, LiveDeviceResolver.TaskLiveDevice> deviceByRider = new HashMap<>();

        List<TaskVo> result = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            TaskAssignment assignment = assignmentByTask.get(task.getId());
            TaskVo vo = TaskVo.from(task, assignment);
            if (assignment != null) {
                vo.setRiderName(riderNameById.get(assignment.getRiderId()));
            }

            MissionOrder order = orderByTask.get(task.getId());
            if (order != null) {
                vo.setOrderNum(order.getOrderNum());
                vo.setTotalAmount(order.getTotalAmount());
                vo.setTotalDistance(order.getTotalDistance());
                vo.setOrderStatus(order.getOrderStatus().name());
                vo.setScheduledTime(order.getScheduledTime());
                vo.setUserConfirmedAt(order.getUserConfirmedAt());
                vo.setRiderConfirmedAt(order.getRiderConfirmedAt());
            }

            // 选定应征（= 成交价/成交机型）；飞手视角未选定时退回本人应征的系统报价
            TaskApplication source = priceSourceByTask.get(task.getId());
            if (source != null) {
                vo.setAircraftModelId(source.getAircraftModelId());
                vo.setQuotedAmount(source.getQuotedAmount());
                AircraftModel model = modelById.get(source.getAircraftModelId());
                if (model != null) {
                    vo.setModelCode(model.getModelCode());
                    vo.setAircraftModelName(model.getDisplayName());
                }
            }

            // 状态矩阵：任务状态 × 订单状态 × 撮合状态
            vo.setActionHint(TaskActionHints.hint(task.getTaskStatus(),
                    order != null ? order.getOrderStatus() : null,
                    task.getMatchStatus()));

            // 1B-4b：任务→设备映射（deviceId/liveState），同一飞手的设备只解析一次
            LiveDeviceResolver.TaskLiveDevice liveDevice = assignment == null
                    ? LiveDeviceResolver.TaskLiveDevice.idle()
                    : deviceByRider.computeIfAbsent(assignment.getRiderId(), liveDeviceResolver::resolveForRider);
            vo.setDeviceId(liveDevice.deviceId());
            vo.setLiveState(liveDevice.liveState());
            result.add(vo);
        }
        return result;
    }
}

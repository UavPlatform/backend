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

    /** 用户端（任务属主）/管理视角详情：完整回显选定应征、成交价与接单飞手。 */
    public TaskVo assemble(Task task) {
        return assembleAll(List.of(task), null).get(0);
    }

    /**
     * 飞手视角详情（/rider/task/detail、/rider/confirm-order）：与列表同一规则，见
     * {@link #assembleForRider(List, Long)}。飞手可查看任意任务详情，所以必须按当前飞手过滤，
     * 不能把选定飞手的报价/机型/成交价泄露给其他飞手。
     */
    public TaskVo assembleForRider(Task task, Long riderId) {
        return assembleAll(List.of(task), riderId).get(0);
    }

    /**
     * 飞手视角的列表批量装配（/rider/my-tasks、/rider/my-tasks/history）。
     *
     * <p>每类关联数据一次批量查询（接单记录、订单、应征、机型、飞手昵称、设备），
     * 查询数与列表长度无关，避免逐条 {@link #assemble} 的 N+1。
     *
     * <p>依据：REQ-BACKEND-001 验收标准 3（应征列表/报价面向发单用户）；飞手之间的报价与成交信息保密目前没有
     * 明确的验收条目，需在 REQ-BACKEND-001 增补验收项或新立 ADR（见 backend#19 说明）。
     *
     * <p>隐私规则（飞手视角）：{@code quotedAmount} 与机型<b>只取当前飞手本人的应征</b>（ADR-0003 平台计价，
     * 飞手不可改价；未应征则为 null），从不取其他飞手的应征。若已选定/已接单的是<b>其他飞手</b>，
     * 接单飞手（riderId/riderName/接单时间/完成说明）与设备直播态不回显。订单成交信息（订单号——发单即生成草稿订单、
     * 成交价、约定时间、确认时间）只在当前飞手<b>就是</b>选定飞手时回显；选定前对所有飞手都不回显。
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
        Set<Long> selectedOtherRider = new java.util.HashSet<>();
        Set<Long> viewerIsSelected = new java.util.HashSet<>();
        for (Task task : tasks) {
            MissionOrder order = orderByTask.get(task.getId());
            TaskApplication selected = order != null && order.getSelectedApplicationId() != null
                    ? applicationById.get(order.getSelectedApplicationId())
                    : selectedByTask.get(task.getId());
            TaskAssignment assignment = assignmentByTask.get(task.getId());
            TaskApplication source;
            if (viewerRiderId == null) {
                source = selected;                         // 属主/管理视角：选定应征
            } else {
                source = ownByTask.get(task.getId());      // 飞手视角：只看本人应征
                boolean otherSelected = (selected != null && !viewerRiderId.equals(selected.getRiderId()))
                        || (assignment != null && !viewerRiderId.equals(assignment.getRiderId()));
                if (otherSelected) {
                    selectedOtherRider.add(task.getId());
                } else if ((selected != null && viewerRiderId.equals(selected.getRiderId()))
                        || (assignment != null && viewerRiderId.equals(assignment.getRiderId()))) {
                    viewerIsSelected.add(task.getId());
                }
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
                        .filter(a -> !selectedOtherRider.contains(a.getTaskId()))
                        .map(TaskAssignment::getRiderId).collect(Collectors.toSet()))
                .forEach(u -> riderNameById.put(u.getId(), u.getUserName()));
        Map<Long, LiveDeviceResolver.TaskLiveDevice> deviceByRider = new HashMap<>();

        List<TaskVo> result = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            boolean hideWinner = selectedOtherRider.contains(task.getId());
            // 订单成交信息（含选定前就生成的草稿订单号）：属主/管理视角，或当前飞手即选定飞手才回显
            boolean showOrderDeal = viewerRiderId == null || viewerIsSelected.contains(task.getId());
            TaskAssignment assignment = hideWinner ? null : assignmentByTask.get(task.getId());
            TaskVo vo = TaskVo.from(task, assignment);
            if (assignment != null) {
                vo.setRiderName(riderNameById.get(assignment.getRiderId()));
            }

            MissionOrder order = orderByTask.get(task.getId());
            if (order != null) {
                vo.setOrderStatus(order.getOrderStatus().name());
                vo.setTotalDistance(order.getTotalDistance());
            }
            if (order != null && showOrderDeal) {
                vo.setOrderNum(order.getOrderNum());
                vo.setTotalAmount(order.getTotalAmount());
                vo.setScheduledTime(order.getScheduledTime());
                vo.setUserConfirmedAt(order.getUserConfirmedAt());
                vo.setRiderConfirmedAt(order.getRiderConfirmedAt());
            }

            // 属主/管理视角：选定应征（= 成交价/成交机型）；飞手视角：仅本人应征
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

package com.uav.task.service.impl;

import com.uav.attachment.service.AttachmentService;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.service.OrderService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.ApplicationStatus;
import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.RouteIdGenerator;
import com.uav.server.util.UserContext;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.entity.TaskWaypoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Task creation and the matching state machine (ADR-0003).
 *
 * <p>State path: create (SEEKING_RIDER, draft order MATCHING) → rider applies
 * (NEGOTIATING) → user selects (AWAITING_PAYMENT) → paid (AWAITING_RIDER_CONFIRM)
 * → rider confirms (CONFIRMED + IN_PROGRESS) → rider delivers (PENDING_ACCEPTANCE)
 * → user confirms (CLOSED, order COMPLETED).
 */
@Service
@Slf4j
public class TaskLifecycleService {

    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final TaskApplicationRepository taskApplicationRepository;
    private final AttachmentService attachmentService;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public TaskLifecycleService(TaskRepository taskRepository,
                                TaskAssignmentRepository taskAssignmentRepository,
                                TaskApplicationRepository taskApplicationRepository,
                                AttachmentService attachmentService,
                                OrderRepository orderRepository,
                                OrderService orderService) {
        this.taskRepository = taskRepository;
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.taskApplicationRepository = taskApplicationRepository;
        this.attachmentService = attachmentService;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task createTask(TaskDto dto) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ApiErrorCode.INVALID_PARAM, "用户未登录");
        }
        if (dto.getTaskName() == null || dto.getTaskName().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务名称不能为空");
        }
        if (dto.getType() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务类型不能为空");
        }
        if (dto.getWaypoints() == null || dto.getWaypoints().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "航点列表不能为空");
        }

        Task task = new Task();
        task.setTaskNum(RouteIdGenerator.generate(userId));
        task.setTaskName(dto.getTaskName());
        task.setTaskType(dto.getType());
        task.setTaskStatus(TaskStatus.IDLE);
        task.setMatchStatus(MatchStatus.SEEKING_RIDER);
        task.setUserId(userId);
        task.setDescription(dto.getDescription());
        task.setTaskTime(dto.getTaskTime());
        task.setReward(dto.getReward());
        if (dto.getCargoWeightKg() != null && dto.getCargoWeightKg().signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "货物重量必须为正数");
        }
        task.setCargoWeightKg(dto.getCargoWeightKg());
        task.setCargoCategory(dto.getCargoCategory());

        List<TaskWaypoint> waypoints = dto.getWaypoints().stream()
                .map(wp -> {
                    TaskWaypoint waypoint = new TaskWaypoint();
                    waypoint.setTask(task);
                    waypoint.setOrderIndex(wp.getOrderIndex());
                    waypoint.setLongitude(wp.getLongitude());
                    waypoint.setLatitude(wp.getLatitude());
                    waypoint.setAltitude(wp.getAltitude());
                    return waypoint;
                })
                .collect(Collectors.toList());

        task.setWaypoints(waypoints);
        Task saved = taskRepository.save(task);
        MissionOrder order = orderService.createOrder(userId, saved.getTaskNum());
        log.info("任务创建成功（撮合态 {}），编号: {}, 用户ID: {}, 类型: {}, 草稿订单: {}",
                saved.getMatchStatus(), saved.getTaskNum(), userId, dto.getType(), order.getOrderNum());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long id, Long userId) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.INVALID_PARAM, "无权删除该任务");
        }
        if (task.getTaskStatus() == TaskStatus.IN_PROGRESS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务执行中，无法删除");
        }

        orderRepository.findByTaskId(task.getId()).ifPresent(order -> {
            OrderStatus status = order.getOrderStatus();
            if (status == OrderStatus.PAID || status == OrderStatus.WAITING_CONFIRM
                    || status == OrderStatus.COMPLETED || status == OrderStatus.REFUNDED
                    || status == OrderStatus.DISPUTED) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                        "任务存在已支付或已完成的订单，禁止删除");
            }
        });

        taskAssignmentRepository.findByTaskId(task.getId())
                .ifPresent(ta -> {
                    taskAssignmentRepository.delete(ta);
                    log.info("任务 {} 关联的接单记录已清理", task.getTaskNum());
                });

        List<TaskApplication> applications = taskApplicationRepository.findByTaskId(task.getId());
        if (!applications.isEmpty()) {
            taskApplicationRepository.deleteAll(applications);
            log.info("任务 {} 关联的 {} 条应征记录已清理", task.getTaskNum(), applications.size());
        }

        orderRepository.findByTaskId(task.getId())
                .ifPresent(order -> {
                    orderRepository.delete(order);
                    log.info("任务 {} 关联的订单 {} 已清理", task.getTaskNum(), order.getOrderNum());
                });

        taskRepository.delete(task);
        log.info("任务删除成功，编号: {}, 用户ID: {}", task.getTaskNum(), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Task selectRider(String taskNum, Long userId, Long applicationId, LocalDateTime scheduledTime) {
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权操作此任务");
        }
        if (applicationId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "applicationId 不能为空");
        }
        if (scheduledTime == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "请填写约定作业时间（scheduledTime）");
        }

        MissionOrder order = orderRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                        "任务缺少关联订单，无法下单"));

        TaskApplication application = taskApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                        "应征记录不存在"));
        if (!application.getTaskId().equals(task.getId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "应征记录不属于该任务");
        }
        if (application.getStatus() == ApplicationStatus.CLOSED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.MATCH_STATUS_INVALID,
                    "该应征已被其他选定关闭，不能下单");
        }

        boolean alreadyPaid = order.getOrderStatus() == OrderStatus.PAID;
        if (!alreadyPaid && order.getOrderStatus() != OrderStatus.MATCHING
                && order.getOrderStatus() != OrderStatus.PENDING
                && order.getOrderStatus() != OrderStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "当前订单状态不允许选定下单: " + order.getOrderStatus().getDesc());
        }
        MatchStatus target = alreadyPaid ? MatchStatus.AWAITING_RIDER_CONFIRM : MatchStatus.AWAITING_PAYMENT;
        MatchStatus.requireTransition(task.getMatchStatus(), target);

        if (!alreadyPaid) {
            orderRepository.findByUserIdAndOrderStatusForUpdate(userId, OrderStatus.PENDING)
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(order.getId())) {
                            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_ALREADY_EXISTS);
                        }
                    });
        }

        BigDecimal quotedAmount = application.getQuotedAmount();
        if (quotedAmount == null || quotedAmount.signum() <= 0) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "系统报价无效，无法下单");
        }

        List<TaskApplication> applications = taskApplicationRepository.findByTaskIdOrderByCreateTimeAsc(task.getId());
        for (TaskApplication item : applications) {
            item.setStatus(item.getId().equals(application.getId())
                    ? ApplicationStatus.SELECTED : ApplicationStatus.CLOSED);
            taskApplicationRepository.save(item);
        }

        order.setSelectedApplicationId(application.getId());
        order.setScheduledTime(scheduledTime);
        order.setUserConfirmedAt(LocalDateTime.now());
        order.setRiderConfirmedAt(null);
        order.setTotalAmount(quotedAmount);
        if (!alreadyPaid) {
            order.setOrderStatus(OrderStatus.PENDING);
        }
        orderRepository.save(order);

        task.setReward(quotedAmount.doubleValue());
        task.setMatchStatus(target);
        Task saved = taskRepository.save(task);
        log.info("用户选定应征下单: taskNum={}, applicationId={}, riderId={}, quotedAmount={}, scheduledTime={}, 订单={}",
                taskNum, application.getId(), application.getRiderId(), quotedAmount, scheduledTime,
                order.getOrderNum());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task riderConfirmOrder(String taskNum, Long riderId) {
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        MatchStatus.requireTransition(task.getMatchStatus(), MatchStatus.CONFIRMED);

        MissionOrder order = orderRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        if (order.getOrderStatus() != OrderStatus.PAID) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "订单尚未支付，飞手不能确认接单");
        }
        if (order.getUserConfirmedAt() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.DOUBLE_CONFIRM_REQUIRED,
                    "缺少用户确认的约定时间，无法完成双确认");
        }

        TaskApplication selected = taskApplicationRepository
                .findByTaskIdAndStatus(task.getId(), ApplicationStatus.SELECTED)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.MATCH_STATUS_INVALID,
                        "任务没有已选定的应征记录"));
        if (!selected.getRiderId().equals(riderId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION,
                    "仅被选定的飞手可以确认此任务");
        }

        order.setRiderConfirmedAt(LocalDateTime.now());
        orderRepository.save(order);

        TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId())
                .orElseGet(() -> {
                    TaskAssignment created = new TaskAssignment();
                    created.setTaskId(task.getId());
                    created.setRiderId(riderId);
                    created.setAcceptTime(LocalDateTime.now());
                    return created;
                });
        if (!assignment.getRiderId().equals(riderId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.MATCH_STATUS_INVALID,
                    "任务已由其他飞手接单");
        }
        taskAssignmentRepository.save(assignment);

        task.setMatchStatus(MatchStatus.CONFIRMED);
        taskRepository.save(task);
        log.info("飞手确认接单: taskNum={}, riderId={}, riderConfirmedAt={}", taskNum, riderId,
                order.getRiderConfirmedAt());

        return startExecution(taskNum);
    }

    @Transactional(rollbackFor = Exception.class)
    public Task startExecution(String taskNum) {
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

        if (task.getTaskStatus() == TaskStatus.IN_PROGRESS) {
            return task;
        }
        if (task.getMatchStatus() != MatchStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.DOUBLE_CONFIRM_REQUIRED,
                    "双方尚未确认，当前撮合状态: " + task.getMatchStatus());
        }
        MissionOrder order = orderRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        if (order.getOrderStatus() != OrderStatus.PAID
                || order.getUserConfirmedAt() == null
                || order.getRiderConfirmedAt() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.DOUBLE_CONFIRM_REQUIRED,
                    "订单未支付或缺少任一方的确认时间，任务不能开始执行");
        }

        task.setTaskStatus(TaskStatus.IN_PROGRESS);
        Task saved = taskRepository.save(task);
        log.info("双确认门禁放行，任务进入执行: taskNum={}", taskNum);
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public void riderCancelTask(String taskNum, Long riderId) {
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

        TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "接单记录不存在"));
        if (!assignment.getRiderId().equals(riderId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.INVALID_PARAM, "无权取消此任务");
        }
        if (task.getTaskStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务状态不允许取消");
        }

        MatchStatus.requireTransition(task.getMatchStatus(), MatchStatus.NEGOTIATING);
        task.setTaskStatus(TaskStatus.IDLE);
        task.setMatchStatus(MatchStatus.NEGOTIATING);
        taskRepository.save(task);
        taskAssignmentRepository.delete(assignment);

        log.info("骑手ID {} 取消任务 {}, 任务重新开放撮合", riderId, taskNum);
    }

    @Transactional(rollbackFor = Exception.class)
    public void riderCompleteTask(String taskNum, Long riderId, String note) {
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

        TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "接单记录不存在"));
        if (!assignment.getRiderId().equals(riderId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.INVALID_PARAM, "无权完成此任务");
        }
        if (task.getTaskStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务状态不允许完成");
        }
        String completeNote = null;
        if (note != null && !note.isBlank()) {
            String trimmed = note.trim();
            if (trimmed.length() > 500) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "完成说明不能超过 500 字");
            }
            completeNote = trimmed;
        }
        requireEvidence(taskNum);

        MatchStatus.requireTransition(task.getMatchStatus(), MatchStatus.PENDING_ACCEPTANCE);
        task.setTaskStatus(TaskStatus.COMPLETED);
        task.setMatchStatus(MatchStatus.PENDING_ACCEPTANCE);
        taskRepository.save(task);

        assignment.setCompleteTime(LocalDateTime.now());
        assignment.setCompleteNote(completeNote);
        taskAssignmentRepository.save(assignment);

        orderRepository.findByTaskId(task.getId()).ifPresent(order -> {
            order.setExecutedAt(LocalDateTime.now());
            order.setOrderStatus(OrderStatus.WAITING_CONFIRM);
            orderRepository.save(order);
        });

        log.info("任务 {} 已交付（履约证据齐备），等待用户ID {} 确认", taskNum, task.getUserId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void userConfirmTask(String taskNum, Long userId) {
        Task task = taskRepository.findByTaskNum(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权确认此任务");
        }
        if (task.getTaskStatus() != TaskStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务尚未完成，无法确认");
        }
        requireEvidence(taskNum);

        orderRepository.findByTaskId(task.getId()).ifPresentOrElse(
                order -> {
                    if (order.getOrderStatus() != OrderStatus.WAITING_CONFIRM) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                                "订单状态不允许确认");
                    }
                    MatchStatus.requireTransition(task.getMatchStatus(), MatchStatus.CLOSED);
                    task.setMatchStatus(MatchStatus.CLOSED);
                    taskRepository.save(task);
                    order.setOrderStatus(OrderStatus.COMPLETED);
                    orderRepository.save(order);
                    log.info("用户ID {} 确认收货，订单 {} 已完成并结案", userId, order.getOrderNum());
                },
                () -> log.warn("任务 {} 未找到关联订单，跳过确认", taskNum)
        );
    }

    private void requireEvidence(String taskNum) {
        if (!attachmentService.hasVerifiedEvidence(taskNum)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.DELIVERY_EVIDENCE_REQUIRED,
                    "尚未上传履约证据（照片/视频等），不能推进完成确认");
        }
    }
}

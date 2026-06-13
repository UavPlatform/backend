package com.uav.task.service.impl;

import com.uav.order.mapper.OrderRepository;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.entity.TaskWaypoint;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.RouteIdGenerator;
import com.uav.server.util.UserContext;
import com.uav.order.service.OrderService;
import com.uav.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TaskServiceImpl implements TaskService {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @Transactional(rollbackFor = Exception.class)
    @Override
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
        task.setUserId(userId);
        task.setDescription(dto.getDescription());

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
        orderService.createOrder(userId, saved.getTaskNum());
        log.info("任务创建成功，编号: {}, 用户ID: {}, 类型: {}", saved.getTaskNum(), userId, dto.getType());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Task> getTasksByUser(Long userId, int page, int size) {
        return taskRepository.findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteTask(Long id, Long userId) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.INVALID_PARAM, "无权删除该任务");
        }
        if (task.getTaskStatus() == TaskStatus.IN_PROGRESS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务执行中，无法删除");
        }

        taskAssignmentRepository.findByTaskId(task.getId())
                .ifPresent(ta -> {
                    taskAssignmentRepository.delete(ta);
                    log.info("任务 {} 关联的接单记录已清理", task.getTaskNum());
                });

        orderRepository.findByTaskId(task.getId())
                .ifPresent(order -> {
                    orderRepository.delete(order);
                    log.info("任务 {} 关联的订单 {} 已清理", task.getTaskNum(), order.getOrderNum());
                });

        taskRepository.delete(task);
        log.info("任务删除成功，编号: {}, 用户ID: {}", task.getTaskNum(), userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Task getTaskByTaskNum(String taskNum, Long userId) {
        Task task = taskRepository.findByTaskNum(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权查看此任务");
        }
        return task;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> getAvailableTasks() {
        return taskRepository.findByTaskStatusOrderByCreateTimeDesc(TaskStatus.IDLE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptTask(String taskNum, Long riderId) {
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

        if (task.getTaskStatus() != TaskStatus.IDLE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "该任务已被接单");
        }
        if (task.getUserId().equals(riderId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "不能接自己的任务");
        }

        task.setTaskStatus(TaskStatus.IN_PROGRESS);
        taskRepository.save(task);

        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(task.getId());
        assignment.setRiderId(riderId);
        assignment.setAcceptTime(LocalDateTime.now());
        taskAssignmentRepository.save(assignment);

        log.info("任务 {} 已被骑手ID {} 接单", taskNum, riderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> getRiderActiveTasks(Long riderId) {
        List<TaskAssignment> assignments = taskAssignmentRepository
                .findByRiderIdAndCompleteTimeIsNullOrderByAcceptTimeDesc(riderId);
        List<Long> taskIds = assignments.stream()
                .map(TaskAssignment::getTaskId)
                .toList();
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return taskRepository.findAllById(taskIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> getRiderAllTasks(Long riderId) {
        List<TaskAssignment> assignments = taskAssignmentRepository.findByRiderIdOrderByAcceptTimeDesc(riderId);
        List<Long> taskIds = assignments.stream()
                .map(TaskAssignment::getTaskId)
                .toList();
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return taskRepository.findAllById(taskIds);
    }

    @Override
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

        task.setTaskStatus(TaskStatus.IDLE);
        taskRepository.save(task);
        taskAssignmentRepository.delete(assignment);

        log.info("骑手ID {} 取消任务 {}, 任务已回到空闲状态", riderId, taskNum);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void riderCompleteTask(String taskNum, Long riderId) {
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

        task.setTaskStatus(TaskStatus.COMPLETED);
        taskRepository.save(task);

        assignment.setCompleteTime(LocalDateTime.now());
        taskAssignmentRepository.save(assignment);

        orderRepository.findByTaskId(task.getId()).ifPresent(order -> {
            order.setOrderStatus(OrderStatus.WAITING_CONFIRM);
            orderRepository.save(order);
        });

        log.info("任务 {} 已完成，等待用户ID {} 确认", taskNum, task.getUserId());
    }

    @Override
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

        orderRepository.findByTaskId(task.getId()).ifPresentOrElse(
                order -> {
                    if (order.getOrderStatus() != OrderStatus.WAITING_CONFIRM) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                                "订单状态不允许确认");
                    }
                    order.setOrderStatus(OrderStatus.COMPLETED);
                    orderRepository.save(order);
                    log.info("用户ID {} 确认收货，订单 {} 已完成", userId, order.getOrderNum());
                },
                () -> log.warn("任务 {} 未找到关联订单，跳过确认", taskNum)
        );
    }
}

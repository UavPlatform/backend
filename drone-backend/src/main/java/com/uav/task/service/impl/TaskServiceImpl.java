package com.uav.task.service.impl;

import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.vo.RiderStatsVO;
import com.uav.task.service.TaskService;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Facade for the task domain: delegates matching writes to {@link TaskLifecycleService},
 * execution/delivery/acceptance to {@link TaskDeliveryService} and reads to {@link TaskQueryService}. Controllers keep a single entry point.
 */
@Service
public class TaskServiceImpl implements TaskService {

    private final TaskLifecycleService lifecycleService;
    private final TaskDeliveryService deliveryService;
    private final TaskQueryService queryService;

    public TaskServiceImpl(TaskLifecycleService lifecycleService, TaskDeliveryService deliveryService,
                           TaskQueryService queryService) {
        this.lifecycleService = lifecycleService;
        this.deliveryService = deliveryService;
        this.queryService = queryService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task createTask(TaskDto dto) {
        return lifecycleService.createTask(dto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Task> getTasksByUser(Long userId, int page, int size) {
        return queryService.getTasksByUser(userId, page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long id, Long userId) {
        lifecycleService.deleteTask(id, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Task getTaskByTaskNum(String taskNum, Long userId) {
        return queryService.getTaskByTaskNum(taskNum, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Task getTaskByTaskNum(String taskNum) {
        return queryService.getTaskByTaskNum(taskNum);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> getAvailableTasks() {
        return queryService.getAvailableTasks();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task selectRider(String taskNum, Long userId, Long applicationId, LocalDateTime scheduledTime) {
        return lifecycleService.selectRider(taskNum, userId, applicationId, scheduledTime);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task riderConfirmOrder(String taskNum, Long riderId) {
        return lifecycleService.riderConfirmOrder(taskNum, riderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task startExecution(String taskNum) {
        return lifecycleService.startExecution(taskNum);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> getRiderActiveTasks(Long riderId) {
        return queryService.getRiderActiveTasks(riderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> getRiderAllTasks(Long riderId) {
        return queryService.getRiderAllTasks(riderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void riderCancelTask(String taskNum, Long riderId) {
        deliveryService.riderCancelTask(taskNum, riderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void riderCompleteTask(String taskNum, Long riderId, String note) {
        deliveryService.riderCompleteTask(taskNum, riderId, note);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void userConfirmTask(String taskNum, Long userId) {
        deliveryService.userConfirmTask(taskNum, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public RiderStatsVO getRiderStats(Long riderId) {
        return queryService.getRiderStats(riderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiderStatsVO> getRecommendedRiders() {
        return queryService.getRecommendedRiders();
    }
}

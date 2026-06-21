package com.uav.task.service;

import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import org.springframework.data.domain.Page;

import java.util.List;

public interface TaskService {
    Task createTask(TaskDto dto);

    Page<Task> getTasksByUser(Long userId, int page, int size);

    void deleteTask(Long id, Long userId);

    Task getTaskByTaskNum(String taskNum, Long userId);

    /** 飞手查看任务详情，不校验归属 */
    Task getTaskByTaskNum(String taskNum);

    List<Task> getAvailableTasks();

    void acceptTask(String taskNum, Long riderId);

    List<Task> getRiderActiveTasks(Long riderId);

    List<Task> getRiderAllTasks(Long riderId);

    void riderCancelTask(String taskNum, Long riderId);

    void riderCompleteTask(String taskNum, Long riderId);

    void userConfirmTask(String taskNum, Long userId);
}

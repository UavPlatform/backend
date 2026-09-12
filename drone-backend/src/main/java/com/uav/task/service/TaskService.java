package com.uav.task.service;

import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import org.springframework.data.domain.Page;

import com.uav.task.pojo.vo.RiderStatsVO;

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

    /**
     * 飞手完成任务（1B-9a）：note 为可选完成说明（≤500 字符，落 task_assignment.complete_note）。
     */
    void riderCompleteTask(String taskNum, Long riderId, String note);

    void userConfirmTask(String taskNum, Long userId);

    /** 飞手统计（今日接单、总完成、总收益） */
    RiderStatsVO getRiderStats(Long riderId);

    /** 推荐飞手列表（按完成量降序） */
    List<RiderStatsVO> getRecommendedRiders();
}

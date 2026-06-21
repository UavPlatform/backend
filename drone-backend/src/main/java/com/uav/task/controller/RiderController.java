package com.uav.task.controller;

import com.uav.server.annotation.RequireDrone;
import com.uav.server.annotation.RequireRole;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.vo.TaskVo;
import com.uav.server.result.Result;
import com.uav.task.pojo.vo.TaskPageVO;
import com.uav.server.annotation.OperationLog;
import com.uav.server.util.UserContext;
import com.uav.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequireRole(1)
@Tag(name = "Rider API", description = "骑手接单接口")
@RestController
@RequestMapping("/rider")
@Slf4j
public class RiderController {

    @Autowired
    private TaskService taskService;

    // ── 只读接口：不需要绑定无人机 ──

    @OperationLog("查看任务广场")
    @Operation(summary = "任务广场", description = "骑手查看所有可接的任务")
    @GetMapping("/square")
    public Result<TaskPageVO> listAvailableTasks() {
        List<Task> tasks = taskService.getAvailableTasks();
        List<TaskVo> taskVos = tasks.stream().map(TaskVo::from).toList();
        return Result.success(new TaskPageVO(taskVos, 0, 1, taskVos.size()));
    }

    @OperationLog("查看进行中的任务")
    @Operation(summary = "进行中的任务", description = "骑手查看自己正在执行的任务")
    @GetMapping("/my-tasks")
    public Result<TaskPageVO> getMyActiveTasks() {
        Long riderId = UserContext.getUserId();
        List<Task> tasks = taskService.getRiderActiveTasks(riderId);
        List<TaskVo> taskVos = tasks.stream().map(TaskVo::from).toList();
        return Result.success(new TaskPageVO(taskVos, 0, 1,taskVos.size()));
    }

    @OperationLog("查看全部接单历史")
    @Operation(summary = "接单历史", description = "骑手查看自己的全部接单记录")
    @GetMapping("/my-tasks/history")
    public Result<TaskPageVO> getMyAllTasks() {
        Long riderId = UserContext.getUserId();
        List<Task> tasks = taskService.getRiderAllTasks(riderId);
        List<TaskVo> taskVos = tasks.stream().map(TaskVo::from).toList();
        return Result.success(new TaskPageVO(taskVos, 0, 1, taskVos.size()));
    }

    // ── 写操作：需要绑定无人机 ──

    @RequireDrone
    @OperationLog("接受任务")
    @Operation(summary = "接受任务", description = "骑手接受指定任务",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @PostMapping("/accept")
    public Result<Void> acceptTask(@RequestParam String taskNum) {
        Long riderId = UserContext.getUserId();
        taskService.acceptTask(taskNum, riderId);
        return Result.success("接单成功");
    }

    @RequireDrone
    @OperationLog("取消接单")
    @Operation(summary = "取消接单", description = "骑手取消已接受的任务，任务回到空闲状态",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @PostMapping("/cancel")
    public Result<Void> cancelTask(@RequestParam String taskNum) {
        Long riderId = UserContext.getUserId();
        taskService.riderCancelTask(taskNum, riderId);
        return Result.success("已取消接单");
    }

    @RequireDrone
    @OperationLog("完成任务")
    @Operation(summary = "完成任务", description = "骑手完成已接受的任务",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @PostMapping("/complete")
    public Result<Void> completeTask(@RequestParam String taskNum) {
        Long riderId = UserContext.getUserId();
        taskService.riderCompleteTask(taskNum, riderId);
        return Result.success("任务已完成");
    }
}

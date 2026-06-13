package com.uav.task.controller;

import com.uav.server.annotation.RequireRole;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.server.result.Result;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.vo.AmapConfigVO;
import com.uav.task.pojo.vo.TaskPageVO;
import com.uav.task.pojo.vo.TaskVo;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.config.AmapConfig;
import com.uav.server.util.UserContext;
import com.uav.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RequireRole({0,2})
@Tag(name = "Task API", description = "任务创建与管理接口")
@RestController
@RequestMapping("/task")
@Slf4j
public class UserTaskController {

    @Autowired
    private AmapConfig amapConfig;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @GetMapping("/init")
    public Result<AmapConfigVO> init() {
        return Result.success(new AmapConfigVO(amapConfig.getKey(), amapConfig.getSecurityKey()));
    }

    @OperationLog("创建任务")
    @RateLimiter(limit = 10, windowSeconds = 60)
    @Operation(summary = "创建任务", description = "创建新任务，包含任务类型、航点信息等")
    @PostMapping("/create")
    public Result<TaskVo> createTask(@RequestBody TaskDto dto) {
        Task saved = taskService.createTask(dto);
        TaskVo vo = TaskVo.from(saved);
        MissionOrder order = orderRepository.findByTaskId(saved.getId()).orElse(null);
        if (order != null) {
            vo.setOrderNum(order.getOrderNum());
            vo.setTotalAmount(order.getTotalAmount());
            vo.setTotalDistance(order.getTotalDistance());
        }
        return Result.success("任务创建成功", vo);
    }

    @OperationLog("查询任务列表")
    @Operation(summary = "获取当前用户的任务列表")
    @GetMapping("/list")
    public Result<TaskPageVO> listTasks(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getUserId();
        Page<Task> taskPage = taskService.getTasksByUser(userId, page, size);

        List<TaskVo> tasks = taskPage.getContent().stream()
                .map(TaskVo::from)
                .toList();

        TaskPageVO vo = new TaskPageVO();
        vo.setTasks(tasks);
        vo.setCurrentPage(taskPage.getNumber());
        vo.setTotalPages(taskPage.getTotalPages());
        vo.setTotalElements(taskPage.getTotalElements());
        return Result.success("获取成功", vo);
    }

    @OperationLog("查询任务详情")
    @Operation(summary = "获取任务详情", description = "根据任务编号获取详细信息，包含航点列表",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @GetMapping("/detail")
    public Result<TaskVo> getTaskDetail(@RequestParam String taskNum) {
        Long userId = UserContext.getUserId();
        Task task = taskService.getTaskByTaskNum(taskNum, userId);
        TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElse(null);
        TaskVo vo = TaskVo.from(task, assignment);
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElse(null);
        if (order != null) {
            vo.setOrderNum(order.getOrderNum());
            vo.setTotalAmount(order.getTotalAmount());
            vo.setTotalDistance(order.getTotalDistance());
        }
        return Result.success("获取成功", vo);
    }

    @OperationLog("删除任务")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "删除任务", description = "删除指定ID的任务，只能删除自己创建的任务",
            parameters = {@Parameter(name = "id", description = "任务数据库ID", required = true)})
    @DeleteMapping("/delete")
    public Result<Void> deleteTask(@RequestParam Long id) {
        Long userId = UserContext.getUserId();
        taskService.deleteTask(id, userId);
        return Result.success("任务删除成功");
    }

    @OperationLog("确认收货")
    @Operation(summary = "确认收货", description = "骑手完成任务后用户确认收货，订单完结",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @PostMapping("/confirm")
    public Result<Void> confirmTask(@RequestParam String taskNum) {
        Long userId = UserContext.getUserId();
        taskService.userConfirmTask(taskNum, userId);
        return Result.success("确认收货成功");
    }
}

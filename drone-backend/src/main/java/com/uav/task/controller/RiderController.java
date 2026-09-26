package com.uav.task.controller;

import com.uav.server.annotation.RequireDrone;
import com.uav.server.annotation.RequireRole;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.mapper.OrderRepository;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.dto.RiderApplyDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.vo.RiderStatsVO;
import com.uav.task.pojo.vo.TaskActionHints;
import com.uav.task.pojo.vo.TaskApplicationVO;
import com.uav.task.pojo.vo.TaskVo;
import com.uav.server.result.Result;
import com.uav.task.pojo.vo.TaskPageVO;
import com.uav.server.annotation.OperationLog;
import com.uav.server.util.UserContext;
import com.uav.task.service.TaskApplicationService;
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

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private OrderRepository orderRepository;

    // ── 只读接口：不需要绑定无人机 ──

    @OperationLog("飞手查看任务详情")
    @Operation(summary = "任务详情", description = "飞手查看任意任务的详细信息（无需任务归属）",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @GetMapping("/task/detail")
    public Result<TaskVo> getTaskDetail(@RequestParam String taskNum) {
        Task task = taskService.getTaskByTaskNum(taskNum);
        TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElse(null);
        TaskVo vo = TaskVo.from(task, assignment);
        // 1B-9a：补齐订单状态与操作提示（状态矩阵数据与用户端同构）
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElse(null);
        if (order != null) {
            vo.setOrderNum(order.getOrderNum());
            vo.setTotalAmount(order.getTotalAmount());
            vo.setTotalDistance(order.getTotalDistance());
            vo.setOrderStatus(order.getOrderStatus().name());
        }
        vo.setActionHint(TaskActionHints.hint(task.getTaskStatus(),
                order != null ? order.getOrderStatus() : null));
        return Result.success(vo);
    }

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

    @OperationLog("飞手统计")
    @Operation(summary = "飞手统计", description = "当前骑手的今日接单数、总完成数、总收益")
    @GetMapping("/stats")
    public Result<RiderStatsVO> getRiderStats() {
        Long riderId = UserContext.getUserId();
        return Result.success(taskService.getRiderStats(riderId));
    }

    @RequireRole({0, 1, 2})
    @OperationLog("推荐飞手")
    @Operation(summary = "推荐飞手", description = "按完成任务量降序返回飞手列表")
    @GetMapping("/recommended")
    public Result<List<RiderStatsVO>> getRecommendedRiders() {
        return Result.success(taskService.getRecommendedRiders());
    }

    // ── 写操作：需要绑定无人机 ──
    // （应征 /rider/apply 刻意不加 @RequireDrone：设备与机型门禁由
    //  requireTransportDevice 在服务端给出精确错误码，如 UAV_NOT_FOUND、AIRCRAFT_MODEL_REQUIRED）

    @OperationLog("飞手应征")
    @Operation(summary = "飞手应征任务", description = "提交 taskNum + aircraftModelId，服务端按 ADR-0003 平台计价公式"
            + "（航点距离 + 货物重量/类别 + 机型系数）计算并持久化系统报价 quotedAmount；"
            + "不接受客户端金额字段——请求携带 price 等字段一律忽略（不允许改价）。"
            + "设备/机型门禁：UAV_NOT_FOUND / AIRCRAFT_MODEL_REQUIRED / AIRCRAFT_MODEL_NOT_FOUND / "
            + "AIRCRAFT_MODEL_NOT_TRANSPORTABLE / AIRCRAFT_MODEL_MISMATCH；超重拒绝：EXCEEDS_PAYLOAD",
            parameters = {
                    @Parameter(name = "taskNum", description = "任务编号", required = true),
                    @Parameter(name = "aircraftModelId", description = "本次应征使用的机型 ID", required = true)
            })
    @PostMapping("/apply")
    public Result<TaskApplicationVO> apply(@RequestBody RiderApplyDto dto) {
        Long riderId = UserContext.getUserId();
        return Result.success("应征成功",
                taskApplicationService.apply(dto.getTaskNum(), riderId, dto.getAircraftModelId()));
    }

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
    @Operation(summary = "完成任务", description = "骑手完成已接受的任务；note 为可选完成说明（≤500 字）",
            parameters = {
                    @Parameter(name = "taskNum", description = "任务编号", required = true),
                    @Parameter(name = "note", description = "完成说明（可选，≤500 字）", required = false)
            })
    @PostMapping("/complete")
    public Result<Void> completeTask(@RequestParam String taskNum,
                                     @RequestParam(required = false) String note) {
        Long riderId = UserContext.getUserId();
        taskService.riderCompleteTask(taskNum, riderId, note);
        return Result.success("任务已完成");
    }
}

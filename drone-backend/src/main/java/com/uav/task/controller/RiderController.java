package com.uav.task.controller;

import com.uav.server.annotation.RequireDrone;
import com.uav.server.annotation.RequireRole;
import com.uav.server.enums.Role;
import com.uav.task.pojo.dto.RiderApplyDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.vo.RiderStatsVO;
import com.uav.task.pojo.vo.TaskApplicationVO;
import com.uav.task.pojo.vo.TaskVo;
import com.uav.server.result.Result;
import com.uav.task.pojo.vo.TaskPageVO;
import com.uav.server.annotation.OperationLog;
import com.uav.server.util.UserContext;
import com.uav.task.service.TaskApplicationService;
import com.uav.task.service.TaskService;
import com.uav.task.service.TaskVoAssembler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequireRole(Role.RIDER)
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
    private TaskVoAssembler taskVoAssembler;

    // ── 只读接口：不需要绑定无人机 ──

    @OperationLog("飞手查看任务详情")
    @Operation(summary = "任务详情",
            description = "飞手查看任意任务的详细信息（无需任务归属），含撮合状态、货物字段与订单/报价回显",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @GetMapping("/task/detail")
    public Result<TaskVo> getTaskDetail(@RequestParam String taskNum) {
        Task task = taskService.getTaskByTaskNum(taskNum);
        return Result.success(taskVoAssembler.assemble(task));
    }

    @OperationLog("查看任务广场")
    @Operation(summary = "任务广场",
            description = "骑手浏览待撮合的吊运任务（招募中/洽谈中，含货物重量/类别等 cargo 字段），"
                    + "不要求订单已支付——支付发生在用户选定应征之后（ADR-0003）")
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

    @RequireRole({Role.USER, Role.RIDER, Role.ADMIN})
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
            + "AIRCRAFT_MODEL_NOT_TRANSPORTABLE / AIRCRAFT_MODEL_MISMATCH；超重拒绝：EXCEEDS_PAYLOAD；"
            + "撮合状态门禁：任务进入选定/支付/确认/验收阶段后拒绝新应征（MATCH_STATUS_INVALID）",
            parameters = {
                    @Parameter(name = "taskNum", description = "任务编号", required = true),
                    @Parameter(name = "aircraftModelId", description = "本次应征使用的机型 ID", required = true)
            })
    @PostMapping("/apply")
    public Result<TaskApplicationVO> apply(@RequestBody RiderApplyDto dto) {
        Long riderId = UserContext.getUserId();
        return Result.success("应征成功", taskApplicationService.apply(
                dto.getTaskNum(), riderId, dto.getAircraftModelId(), dto.getQuotedAmount()));
    }

    @OperationLog("飞手确认订单")
    @Operation(summary = "飞手确认订单",
            description = "ADR-0003 决定 4：被选定的飞手确认接单与约定作业时间（记 riderConfirmedAt），"
                    + "撮合状态 → CONFIRMED；双确认齐备后任务经门禁推进 IN_PROGRESS（可执飞）。"
                    + "未到待确认阶段返回 MATCH_STATUS_INVALID/ORDER_STATUS_INVALID；非选定飞手返回 NO_PERMISSION；"
                    + "门禁不满足返回 DOUBLE_CONFIRM_REQUIRED",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @PostMapping("/confirm-order")
    public Result<TaskVo> confirmOrder(@RequestParam String taskNum) {
        Long riderId = UserContext.getUserId();
        Task task = taskService.riderConfirmOrder(taskNum, riderId);
        return Result.success("确认成功，任务开始执行", taskVoAssembler.assemble(task));
    }

    @RequireDrone
    @OperationLog("取消接单")
    @Operation(summary = "取消接单", description = "骑手取消执行中的任务，任务回到待撮合（重新开放撮合）",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @PostMapping("/cancel")
    public Result<Void> cancelTask(@RequestParam String taskNum) {
        Long riderId = UserContext.getUserId();
        taskService.riderCancelTask(taskNum, riderId);
        return Result.success("已取消接单");
    }

    @RequireDrone
    @OperationLog("完成任务")
    @Operation(summary = "完成任务",
            description = "骑手交付任务；必须先上传履约证据（attachment），否则 DELIVERY_EVIDENCE_REQUIRED；"
                    + "交付后进入待验收（PENDING_ACCEPTANCE / 订单 WAITING_CONFIRM）。"
                    + "note 为可选完成说明（≤500 字）",
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

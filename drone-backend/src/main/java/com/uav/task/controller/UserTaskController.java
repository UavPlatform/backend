package com.uav.task.controller;

import com.uav.server.annotation.RequireRole;
import com.uav.task.pojo.dto.SelectRiderDto;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.server.result.Result;
import com.uav.task.pojo.vo.TaskApplicationVO;
import com.uav.task.service.TaskApplicationService;
import com.uav.task.pojo.vo.AmapConfigVO;
import com.uav.task.pojo.vo.TaskPageVO;
import com.uav.task.pojo.vo.TaskVo;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.config.AmapConfig;
import com.uav.server.util.UserContext;
import com.uav.task.service.TaskService;
import com.uav.task.service.TaskVoAssembler;
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
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskVoAssembler taskVoAssembler;

    @Operation(summary = "获取地图配置", description = "返回高德地图 JS API 所需的 key 与安全密钥")
    @GetMapping("/init")
    public Result<AmapConfigVO> init() {
        return Result.success(new AmapConfigVO(amapConfig.getKey(), amapConfig.getSecurityKey()));
    }

    @OperationLog("创建任务")
    @RateLimiter(limit = 10, windowSeconds = 60)
    @Operation(summary = "创建任务",
            description = "创建新任务（含任务类型、航点、货物字段），同时生成待撮合草稿订单（MATCHING），"
                    + "不强制立即支付（ADR-0003 决定 3）；金额在选定应征时锁定")
    @PostMapping("/create")
    public Result<TaskVo> createTask(@RequestBody TaskDto dto) {
        Task saved = taskService.createTask(dto);
        return Result.success("任务创建成功", taskVoAssembler.assemble(saved));
    }

    @OperationLog("查询任务列表")
    @Operation(summary = "获取当前用户的任务列表")
    @GetMapping("/list")
    public Result<TaskPageVO> listTasks(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getUserId();
        Page<Task> taskPage = taskService.getTasksByUser(userId, page, size);

        List<TaskVo> tasks = taskPage.getContent().stream()
                .map(taskVoAssembler::assemble)
                .toList();

        TaskPageVO vo = new TaskPageVO();
        vo.setTasks(tasks);
        vo.setCurrentPage(taskPage.getNumber());
        vo.setTotalPages(taskPage.getTotalPages());
        vo.setTotalElements(taskPage.getTotalElements());
        return Result.success("获取成功", vo);
    }

    @OperationLog("查询任务详情")
    @Operation(summary = "获取任务详情",
            description = "根据任务编号获取详细信息，含航点、货物字段、撮合状态 matchStatus、"
                    + "约定时间/双方确认时间与选定应征报价（quotedAmount）/机型",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @GetMapping("/detail")
    public Result<TaskVo> getTaskDetail(@RequestParam String taskNum) {
        Long userId = UserContext.getUserId();
        Task task = taskService.getTaskByTaskNum(taskNum, userId);
        return Result.success("获取成功", taskVoAssembler.assemble(task));
    }

    @OperationLog("查询应征列表")
    @Operation(summary = "应征列表",
            description = "任务属主、应征飞手与管理员（role=2，监管端只读）按任务编号查询飞手应征列表："
                    + "飞手、机型、载重、系统报价 quotedAmount、应征时间、状态"
                    + "（非属主非应征的普通用户 403）",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @RequireRole({0, 1, 2})
    @GetMapping("/{taskNum}/applications")
    public Result<List<TaskApplicationVO>> listApplications(@PathVariable String taskNum) {
        Long userId = UserContext.getUserId();
        return Result.success("获取成功", taskApplicationService.listByTask(taskNum, userId, UserContext.getRole()));
    }

    @OperationLog("选定应征下单")
    @Operation(summary = "用户选定应征并下单",
            description = "ADR-0003 决定 3：选定一条应征（其余自动 CLOSED）+ 提交约定作业时间 scheduledTime，"
                    + "服务端锁定订单 totalAmount = 该应征 quotedAmount（严格相等，不允许改价）并转待支付（PENDING）；"
                    + "随后走既有 POST /pay/{orderNum} 支付。撮合状态 → AWAITING_PAYMENT，"
                    + "非法迁移返回 MATCH_STATUS_INVALID；已有待支付订单返回 ORDER_ALREADY_EXISTS",
            parameters = {
                    @Parameter(name = "taskNum", description = "任务编号", required = true)
            })
    @PostMapping("/{taskNum}/select-rider")
    public Result<TaskVo> selectRider(@PathVariable String taskNum,
                                      @RequestBody SelectRiderDto dto) {
        Long userId = UserContext.getUserId();
        Task task = taskService.selectRider(taskNum, userId, dto.getApplicationId(), dto.getScheduledTime());
        return Result.success("下单成功，待支付", taskVoAssembler.assemble(task));
    }

    @OperationLog("删除任务")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "删除任务", description = "删除指定ID的任务，只能删除自己创建的任务；已支付/已完成订单的任务禁止删除",
            parameters = {@Parameter(name = "id", description = "任务数据库ID", required = true)})
    @DeleteMapping("/delete")
    public Result<Void> deleteTask(@RequestParam Long id) {
        Long userId = UserContext.getUserId();
        taskService.deleteTask(id, userId);
        return Result.success("任务删除成功");
    }

    @OperationLog("确认收货")
    @Operation(summary = "确认收货",
            description = "飞手上传履约证据并交付后用户确认收货：须已存在 attachment 证据（否则 "
                    + "DELIVERY_EVIDENCE_REQUIRED），确认后订单 COMPLETED、撮合状态 CLOSED",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @PostMapping("/confirm")
    public Result<Void> confirmTask(@RequestParam String taskNum) {
        Long userId = UserContext.getUserId();
        taskService.userConfirmTask(taskNum, userId);
        return Result.success("确认收货成功");
    }
}

package com.uav.admin.controller;

import com.uav.admin.pojo.vo.AdminOrderVo;
import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.admin.pojo.vo.AdminPilotDetailVo;
import com.uav.admin.pojo.vo.AdminPilotVo;
import com.uav.admin.pojo.vo.AdminTaskVo;
import com.uav.admin.pojo.vo.AdminUserDetailVo;
import com.uav.admin.pojo.vo.AdminUserVo;
import com.uav.admin.service.AdminQueryService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1B-5 管理端业务查询（裁决 Q6/Q7=A，只读）：全平台任务/订单分页列表与详情。
 * 订单状态码映射（C5 契约）：0 待支付 / 1 已支付 / 2 已取消 / 3 已退款 / 4 已完成 / 5 待验收。
 */
@Tag(name = "Admin Query API", description = "管理端业务查询（只读）")
@RestController
@RequestMapping("/admin")
@RequireRole(2)
@Slf4j
public class AdminQueryController {

    private final AdminQueryService adminQueryService;

    public AdminQueryController(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @Operation(summary = "全平台订单分页列表",
            description = "支持按订单状态（枚举名或状态码）与订单号/任务号精确过滤；page 从 0 起",
            parameters = {
                    @Parameter(name = "page", description = "页码（从 0 起）"),
                    @Parameter(name = "size", description = "每页条数（默认 20，上限 100）"),
                    @Parameter(name = "status", description = "订单状态过滤：枚举名（PAID）或状态码（1）"),
                    @Parameter(name = "orderNum", description = "订单号精确过滤"),
                    @Parameter(name = "taskNum", description = "任务编号精确过滤")
            })
    @GetMapping("/orders")
    public Result<AdminPageVo<AdminOrderVo>> listOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderNum,
            @RequestParam(required = false) String taskNum) {
        return Result.success(adminQueryService.listOrders(page, size, status, orderNum, taskNum));
    }

    @Operation(summary = "订单详情", parameters = {
            @Parameter(name = "orderNum", description = "订单号", required = true)})
    @GetMapping("/orders/{orderNum}")
    public Result<AdminOrderVo> orderDetail(@PathVariable String orderNum) {
        return Result.success(adminQueryService.getOrderDetail(orderNum));
    }

    @Operation(summary = "全平台任务分页列表",
            description = "支持按任务状态（枚举名：IDLE/IN_PROGRESS/COMPLETED）与任务号精确过滤；含订单双状态与操作提示",
            parameters = {
                    @Parameter(name = "page", description = "页码（从 0 起）"),
                    @Parameter(name = "size", description = "每页条数（默认 20，上限 100）"),
                    @Parameter(name = "status", description = "任务状态过滤：IDLE / IN_PROGRESS / COMPLETED"),
                    @Parameter(name = "taskNum", description = "任务编号精确过滤")
            })
    @GetMapping("/tasks")
    public Result<AdminPageVo<AdminTaskVo>> listTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskNum) {
        return Result.success(adminQueryService.listTasks(page, size, status, taskNum));
    }

    @Operation(summary = "任务详情", description = "含订单双状态、飞手与完成说明、操作提示",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @GetMapping("/tasks/{taskNum}")
    public Result<AdminTaskVo> taskDetail(@PathVariable String taskNum) {
        return Result.success(adminQueryService.getTaskDetail(taskNum));
    }

    // ---------- 注册主体（TASK-BACKEND-006 / REQ-FRONTEND-001 / ADR-0004）----------

    @Operation(summary = "注册用户分页列表",
            description = "注册普通用户（role=0，飞手见 /admin/pilots）；含名下订单数；page 从 0 起",
            parameters = {
                    @Parameter(name = "page", description = "页码（从 0 起）"),
                    @Parameter(name = "size", description = "每页条数（默认 20，上限 100）"),
                    @Parameter(name = "keyword", description = "用户名关键字（模糊匹配，忽略大小写）"),
                    @Parameter(name = "status", description = "账号状态筛选（1 正常 / 0 停用）")
            })
    @GetMapping("/users")
    public Result<AdminPageVo<AdminUserVo>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return Result.success(adminQueryService.listUsers(page, size, keyword, status));
    }

    @Operation(summary = "用户详情",
            description = "基本信息 + 关联订单摘要（orderNum、任务、状态、金额）；飞手请用 /admin/pilots/{userId}",
            parameters = {@Parameter(name = "userId", description = "用户ID", required = true)})
    @GetMapping("/users/{userId}")
    public Result<AdminUserDetailVo> userDetail(@PathVariable Long userId) {
        return Result.success(adminQueryService.getUserDetail(userId));
    }

    @Operation(summary = "注册飞手分页列表",
            description = "注册飞手（role=1）；含绑定无人机数、在线无人机数与累计完成单；page 从 0 起",
            parameters = {
                    @Parameter(name = "page", description = "页码（从 0 起）"),
                    @Parameter(name = "size", description = "每页条数（默认 20，上限 100）"),
                    @Parameter(name = "keyword", description = "用户名关键字（模糊匹配，忽略大小写）")
            })
    @GetMapping("/pilots")
    public Result<AdminPageVo<AdminPilotVo>> listPilots(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return Result.success(adminQueryService.listPilots(page, size, keyword));
    }

    @Operation(summary = "飞手详情",
            description = "基本信息 + 绑定无人机表（djiId、机型名、在线、可用）+ 关联订单；"
                    + "无人机启用/禁用复用 POST /admin/uav/available（按 deviceId=djiId 启停）",
            parameters = {@Parameter(name = "userId", description = "飞手ID", required = true)})
    @GetMapping("/pilots/{userId}")
    public Result<AdminPilotDetailVo> pilotDetail(@PathVariable Long userId) {
        return Result.success(adminQueryService.getPilotDetail(userId));
    }
}

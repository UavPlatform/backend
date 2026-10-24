package com.uav.dispatch.controller;

import com.uav.dispatch.pojo.vo.DispatchNotificationPageVO;
import com.uav.dispatch.service.DispatchNotificationService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/dispatch/notifications") @RequireRole({0, 1})
@Tag(name = "Dispatch Notification API", description = "订单事件站内通知，按收件人隔离；不声称已送达手机系统推送")
public class DispatchNotificationController {
    private final DispatchNotificationService service;
    public DispatchNotificationController(DispatchNotificationService service) { this.service = service; }
    @GetMapping @Operation(summary = "分页读取本人派单通知", description = "时间倒序每页20条，包含订单ID、收件人角色和已读状态；只读不自动清空未读，点击后打开订单当前阶段")
    public Result<DispatchNotificationPageVO> dispatchNotifications(@RequestParam(defaultValue = "0") String page) {
        try { return Result.success(service.list(UserContext.getUserId(), Integer.parseInt(page))); }
        catch (NumberFormatException e) { throw new BusinessException(ApiErrorCode.INVALID_PARAM); }
    }
    @GetMapping("/unread-count") @Operation(summary = "读取本人派单通知未读数")
    public Result<Long> dispatchNotificationUnreadCount() { return Result.success(service.unread(UserContext.getUserId())); }
    @PostMapping("/{notificationId}/read") @Operation(summary = "标记一条本人通知已读", description = "只作用于所选通知，重复调用幂等，不会误清除其他或稍后收到的通知")
    public Result<Void> readDispatchNotification(@PathVariable String notificationId) {
        service.read(UserContext.getUserId(), notificationId); return Result.success();
    }
}

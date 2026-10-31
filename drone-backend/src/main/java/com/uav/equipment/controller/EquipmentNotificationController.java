package com.uav.equipment.controller;

import com.uav.equipment.pojo.vo.EquipmentNotificationPageVO;
import com.uav.equipment.service.EquipmentAccess;
import com.uav.equipment.service.EquipmentNotificationService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/** REQ-ALL-XC-005 minimal equipment/borrow inbox (push still TODO). */
@RestController
@RequestMapping("/equipment/notifications")
@RequireRole({1, 2})
@Tag(name = "Equipment Notification API", description = "借机相关站内通知（落库）；按角色 audience 隔离；手机推送尚未接入")
public class EquipmentNotificationController {
    private final EquipmentNotificationService service;
    public EquipmentNotificationController(EquipmentNotificationService service) { this.service = service; }

    @GetMapping
    @Operation(operationId = "listEquipmentNotifications", summary = "借机通知列表",
            description = "按当前登录角色过滤：飞手只见 PILOT，管理员只见 ADMIN（同数字 id 互不可见）")
    public Result<EquipmentNotificationPageVO> list(@RequestParam(defaultValue = "0") String page) {
        return Result.success(service.list(UserContext.getUserId(), UserContext.getRole(), EquipmentAccess.parse(page)));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(operationId = "readEquipmentNotification", summary = "标记借机通知已读",
            description = "仅能标记本角色 audience 下、属于自己的通知")
    public Result<Void> read(@PathVariable String notificationId) {
        service.read(UserContext.getUserId(), UserContext.getRole(), notificationId);
        return Result.success();
    }
}

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
@Tag(name = "Equipment Notification API", description = "借机相关站内通知（落库）；手机推送尚未接入")
public class EquipmentNotificationController {
    private final EquipmentNotificationService service;
    public EquipmentNotificationController(EquipmentNotificationService service) { this.service = service; }

    @GetMapping
    @Operation(operationId = "listEquipmentNotifications", summary = "借机通知列表")
    public Result<EquipmentNotificationPageVO> list(@RequestParam(defaultValue = "0") String page) {
        return Result.success(service.list(UserContext.getUserId(), EquipmentAccess.parse(page)));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(operationId = "readEquipmentNotification", summary = "标记借机通知已读")
    public Result<Void> read(@PathVariable String notificationId) {
        service.read(UserContext.getUserId(), notificationId);
        return Result.success();
    }
}

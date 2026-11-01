package com.uav.live.controller;

import com.uav.flight.service.PilotDeviceAccess;
import com.uav.live.service.AppWebSocketService;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "APP WebSocket API")
@RestController
@RequestMapping("/api")
@Slf4j
public class AppWebSocketController {

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private PilotDeviceAccess pilotDeviceAccess;

    @Operation(
            summary = "申请WebSocket连接",
            description = "设备申请建立WebSocket连接（须为当前飞手派单连机或已批准借用机）",
            responses = {
                    @ApiResponse(responseCode = "200", description = "申请成功"),
                    @ApiResponse(responseCode = "400", description = "申请失败")
            }
    )
    @PostMapping("/ws/request")
    public ResponseEntity<Map<String, Object>> requestConnection(@RequestParam String deviceId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "未登录"));
        }
        Integer role = UserContext.getRole();
        if (role == null || role != 1 || !pilotDeviceAccess.mayUse(userId, deviceId)) {
            log.warn("设备连接申请被拒（无设备使用权）: userId={}, deviceId={}", userId, deviceId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "无权为该设备申请连接"));
        }

        log.info("设备 {} 申请WebSocket连接", deviceId);
        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = appWebSocketService.requestConnection(deviceId);
            if (success) {
                result.put("success", true);
                result.put("message", "连接申请已提交，请在App端发起WebSocket连接");
                return ResponseEntity.ok(result);
            } else {
                result.put("success", false);
                result.put("message", "设备已连接");
                return ResponseEntity.badRequest().body(result);
            }
        } catch (Exception e) {
            log.error("申请连接失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "申请失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}

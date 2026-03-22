package com.drone.controller.appController;

import com.drone.service.AppWebSocketService;
import com.drone.server.annotation.SkipJwt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@SkipJwt
public class AppWebSocketController {

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Operation(
            summary = "申请WebSocket连接",
            description = "设备申请建立WebSocket连接",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "申请成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"message\": \"连接申请已提交，请在App端发起WebSocket连接\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "申请失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"设备已连接\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/ws/request")
    public ResponseEntity<Map<String, Object>> requestConnection(@RequestParam String deviceId) {
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
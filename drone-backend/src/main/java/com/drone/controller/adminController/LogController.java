package com.drone.controller.adminController;

import com.drone.service.LogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Log API")
@RestController
@RequestMapping("/admin/logs")
@Slf4j
public class LogController {

    @Autowired
    private LogService logService;

    @Operation(
            summary = "获取应用日志",
            description = "获取应用程序的日志信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"logs\": [\"2026-03-31 10:00:00.000 [main] INFO com.drone.DroneBackendApplication - Starting DroneBackendApplication on DESKTOP-123456 with PID 12345\", ...]}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/application")
    public ResponseEntity<Map<String, Object>> getApplicationLogs(@RequestParam(defaultValue = "100") int lines){
        log.info("获取应用日志，行数：{}", lines);
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> logs = logService.getApplicationLogs(lines);
            result.put("success", true);
            result.put("logs", logs);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取应用日志失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }

    @Operation(
            summary = "获取错误日志",
            description = "获取应用程序的错误日志信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"logs\": [\"2026-03-31 10:00:00.000 [main] ERROR com.drone.service.impl.AdminServiceImpl - 登录验证失败: 用户名或密码错误\", ...]}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/error")
    public ResponseEntity<Map<String, Object>> getErrorLogs(@RequestParam(defaultValue = "100") int lines){
        log.info("获取错误日志，行数：{}", lines);
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> logs = logService.getErrorLogs(lines);
            result.put("success", true);
            result.put("logs", logs);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取错误日志失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }
}
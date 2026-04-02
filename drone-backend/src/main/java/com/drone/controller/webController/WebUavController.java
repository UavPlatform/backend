package com.drone.controller.webController;

import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.vo.UavVo;
import com.drone.pojo.vo.WebUavStatusVo;
import com.drone.service.WebUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "WebUav API")
@RestController
@RequestMapping("/webUav")
@Slf4j
public class WebUavController {

    @Autowired
    private WebUavService webUavService;

    /**
     * 获取所有的无人机
     * @return
     */
    @Operation(
            summary = "查询无人机",
            description = "获取所有的无人机列表",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功（可能有数据或无数据）",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            // 直接用字符串数组，兼容所有版本
                                            examples = {
                                                    "{\"success\": true, \"uav\": [{\"id\": 1, \"uavName\": \"无人机1\"}, {\"id\": 2, \"uavName\": \"无人机2\"}]}",
                                                    "{\"success\": false, \"message\": \"暂时没有无人机在线\"}"
                                            }
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "查询失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"查询失败\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/getUav")
    public ResponseEntity<Map<String, Object>> getUav(){
        log.info("web端查询目前所有的无人机");
        Map<String, Object> result = new HashMap<>();
        try {
            UavVo[] onlineUavVos = webUavService.getUav();
            if (onlineUavVos == null || onlineUavVos.length == 0) {
                result.put("success", false);
                result.put("message", "系统暂时没有录入无人机");
            } else {
                result.put("success", true);
                result.put("uav", onlineUavVos);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询无人机失败:{}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }

    @Operation(
            summary = "查询单台无人机实时状态",
            description = "根据设备ID查询平台侧最近一次收到的无人机状态",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": \"OK\", \"status\": {\"djiId\": \"123456\", \"wsConnected\": true, \"liveState\": \"RUNNING\", \"latestStatus\": {\"battery\": 85}}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "无人机或状态不存在"
                    )
            }
    )
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getUavStatus(@RequestParam String deviceId) {
        log.info("平台端查询无人机状态，deviceId={}", deviceId);
        WebUavStatusVo status = webUavService.getUavStatus(deviceId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("code", "OK");
        result.put("status", status);
        return ResponseEntity.ok(result);
    }


    /**
     * 查询用户个人观看记录，暂定为管理员权限（虽然现在还没有管理员
     * @param userName 用户名
     * @return 用户的直播记录列表
     */
    @Operation(
            summary = "查询用户个人观看记录（管理员权限",
            description = "根据用户名查询用户的直播观看记录",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"records\": [{\"id\": 1, \"userName\": \"test\", \"djiId\": \"123456\", \"start_time\": \"2026-03-27T10:00:00\", \"end_time\": \"2026-03-27T11:00:00\"}]}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "查询失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"用户未注册\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/getRecord")
    public  ResponseEntity<Map<String, Object>> getUserRecord(@RequestParam String userName, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size){
        log.info("查询用户个人观看记录：{}, page={}, size={}", userName, page, size);
        Map<String, Object> result = new HashMap<>();
        try {
            // 创建分页参数
            Pageable pageable = PageRequest.of(page, size);
            
            // 查询用户直播记录
            Page<UserRecord> recordPage = webUavService.getUserRecord(userName, pageable);
            result.put("success", true);
            result.put("records", recordPage.getContent());
            result.put("total", recordPage.getTotalElements());
            result.put("page", recordPage.getNumber());
            result.put("size", recordPage.getSize());
            result.put("totalPages", recordPage.getTotalPages());
            return ResponseEntity.status(200).body(result);
        } catch (Exception e) {
            log.error("查询用户记录失败:{}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }
}

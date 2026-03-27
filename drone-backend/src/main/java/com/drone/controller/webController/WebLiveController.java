package com.drone.controller.webController;

import com.alibaba.fastjson.JSON;
import com.drone.mapper.UserRecordRepository;
import com.drone.pojo.entity.UserRecord;
import com.drone.server.handler.DroneWebSocketHandler;
import com.drone.server.util.UserContext;
import com.drone.service.TRTCService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Tag(name = "Web Live API")
@RestController
@RequestMapping("/live")
@Slf4j
public class  WebLiveController {

    @Autowired
    private TRTCService trtcService;
    @Autowired
    private DroneWebSocketHandler webSocketHandler;
    @Autowired
    private UserRecordRepository userRecordRepository;

    /**
     * 请求app开播
     * @param deviceId
     * @return
     */
    @Operation(
            summary = "发送开播请求",
            description = "Web端向指定无人机发送开播请求",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "发送成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"message\": \"开播请求已发送\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "设备未连接",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"设备未连接\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "发送失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"发送失败: 错误信息\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/req")
    public ResponseEntity<Map<String, Object>> startLive(@RequestParam String deviceId) {
        Map<String, Object> result = new HashMap<>();
        log.info("请求ID为：{}的无人机开播",deviceId);//可加userid写入日志
        try {
            // 生成TRTC凭证
            String roomId = trtcService.generateRoomId(deviceId);
            String userSig = trtcService.generateUserSig(deviceId);

            //开播命令
            Map<String, Object> command = new HashMap<>();
            command.put("type", "START_LIVE");
            command.put("roomId", roomId);
            command.put("userId", deviceId);
            command.put("userSig", userSig);

            //发送命令
            boolean success = webSocketHandler.sendMessage(deviceId, JSON.toJSONString(command));
            if (success) {
                result.put("success", true);
                result.put("message", "开播请求已发送");
                return ResponseEntity.ok(result);
            } else {
                result.put("success", false);
                result.put("message", "设备未连接");
                return ResponseEntity.status(400).body(result);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "发送失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }


    /**
     * 获取凭证
     * @param deviceId
     * @param webUserId
     * @return
     */
    @Operation(
            summary = "获取拉流凭证",
            description = "Web端获取视频流的凭证",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"roomId\": \"drone_123\", \"userId\": \"web_123456\", \"userSig\": \"{\\\"sdkAppId\\\": 1400123456, \\\"userId\\\": \\\"web_123456\\\", ...}\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "获取失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"生成拉流凭证失败\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/get")
    public ResponseEntity<Map<String, Object>> getPullCredentials(@RequestParam String deviceId, @RequestParam String webUserId, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            //TRTC拉流凭证
            String roomId = trtcService.generateRoomId(deviceId);
            String userSig = trtcService.generateUserSig(webUserId);
            
            // 记录用户直播观看记录
            String userName = UserContext.getUsername();
            if (userName != null) {
                UserRecord record = new UserRecord();
                record.setUserName(userName);
                record.setDjiId(deviceId);
                record.setStart_time(LocalDateTime.now());
                //结束时间暂时设为null
                userRecordRepository.save(record);
                log.info("记录用户直播观看记录: userName={}, deviceId={}", userName, deviceId);
            }
            
            result.put("success", true);
            result.put("roomId", roomId);
            result.put("userId", webUserId);
            result.put("userSig", userSig);
            result.put("wsUrl", "ws://" + request.getServerName() + ":" + request.getServerPort() + "/ws/web?deviceId=" + deviceId);
            log.info("为Web用户 {} 生成拉流凭证，设备ID：{}", webUserId, deviceId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "生成拉流凭证失败: " + e.getMessage());
            log.error("生成拉流凭证失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }


}
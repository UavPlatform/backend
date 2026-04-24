package com.drone.controller.webController;

import com.drone.mapper.UserRecordRepository;
import com.drone.pojo.entity.UserRecord;
import com.drone.server.exception.ApiErrorCode;
import com.drone.server.exception.BusinessException;
import com.drone.server.exception.UnauthorizedException;
import com.drone.server.handler.DroneWebSocketHandler;
import com.drone.server.ws.handler.WsCommandAckResult;
import com.drone.server.util.UserContext;
import com.drone.service.AppWebSocketService;
import com.drone.service.LiveSessionService;
import com.drone.service.TRTCService;
import com.drone.service.WebUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Web Live API")
@RestController
@RequestMapping("/live")
@Slf4j
public class  WebLiveController {
    private static final long LIVE_ACK_TIMEOUT_MILLIS = 5000;
    private static final long LIVE_STARTING_TTL_MILLIS = 15000;

    @Autowired
    private TRTCService trtcService;
    @Autowired
    private DroneWebSocketHandler webSocketHandler;
    @Autowired
    private UserRecordRepository userRecordRepository;
    @Autowired
    private WebUavService webUavService;
    @Autowired
    private AppWebSocketService appWebSocketService;
    @Autowired
    private LiveSessionService liveSessionService;

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
        webUavService.getRegisteredUav(deviceId);
        if (!appWebSocketService.isConnected(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.UAV_NOT_CONNECTED);
        }

        if (liveSessionService.isRunning(deviceId)) {
            Map<String, Object> running = new HashMap<>();
            running.put("success", true);
            running.put("code", ApiErrorCode.LIVE_ALREADY_RUNNING.getCode());
            running.put("message", ApiErrorCode.LIVE_ALREADY_RUNNING.getDefaultMessage());
            running.put("ackConfirmed", true);
            running.put("liveState", liveSessionService.getSnapshot(deviceId).getState().name());
            running.put("roomId", liveSessionService.getSnapshot(deviceId).getRoomId());
            return ResponseEntity.ok(running);
        }
        if (liveSessionService.isStarting(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.LIVE_ALREADY_STARTING);
        }

        Map<String, Object> result = new HashMap<>();
        log.info("请求ID为：{}的无人机开播",deviceId);//可加userid写入日志
        try {
            String roomId = trtcService.generateRoomId(deviceId);
            String userSig = trtcService.generateUserSig(deviceId);
            WsCommandAckResult ackResult = webSocketHandler.sendStartLiveCommand(
                    deviceId,
                    roomId,
                    deviceId,
                    userSig,
                    LIVE_ACK_TIMEOUT_MILLIS,
                    LIVE_STARTING_TTL_MILLIS
            );

            if (!ackResult.isSuccess() && !ackResult.isTimedOut()) {
                throwStartLiveFailure(ackResult);
            }

            result.put("success", true);
            result.put("requestId", ackResult.getRequestId());
            result.put("roomId", roomId);
            result.put("ackConfirmed", !ackResult.isTimedOut());
            if (ackResult.isTimedOut()) {
                result.put("code", "LIVE_START_PENDING");
                result.put("message", "开播命令已发送，等待设备确认");
                result.put("liveState", liveSessionService.getSnapshot(deviceId).getState().name());
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
            }

            result.put("code", "LIVE_STARTED");
            result.put("message", "设备已确认启动图传");
            result.put("liveState", liveSessionService.getSnapshot(deviceId).getState().name());
            return ResponseEntity.ok(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            liveSessionService.markFailed(deviceId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.TRTC_CREDENTIAL_FAILED, "发送图传启动命令失败: " + e.getMessage());
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
        webUavService.getRegisteredUav(deviceId);
        if (!appWebSocketService.isConnected(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.UAV_NOT_CONNECTED);
        }

        Map<String, Object> result = new HashMap<>();
        try {
            String roomId = trtcService.generateRoomId(deviceId);
            String userSig = trtcService.generateUserSig(webUserId);

            String userName = UserContext.getUsername();
            if (userName != null) {
                ensureOpenRecord(userName, deviceId);
            }

            result.put("success", true);
            result.put("code", "OK");
            result.put("roomId", roomId);
            result.put("userId", webUserId);
            result.put("userSig", userSig);
            result.put("sdkAppId", trtcService.getSdkAppId());
            result.put("wsUrl", buildWebSocketUrl(request, deviceId));
            result.put("liveState", liveSessionService.getSnapshot(deviceId).getState().name());
            result.put("ackConfirmed", liveSessionService.isRunning(deviceId));
            log.info("为Web用户 {} 生成拉流凭证，设备ID：{}", webUserId, deviceId);
            return ResponseEntity.ok(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成拉流凭证失败: {}", e.getMessage());
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.TRTC_CREDENTIAL_FAILED, "生成拉流凭证失败: " + e.getMessage());
        }
    }

    @Operation(
            summary = "结束观看会话",
            description = "当前用户退出图传观看时，补齐观看记录结束时间"
    )
    @PostMapping("/close")
    public ResponseEntity<Map<String, Object>> closeLive(@RequestParam String deviceId) {
        webUavService.getRegisteredUav(deviceId);
        String userName = UserContext.getUsername();
        if (userName == null || userName.isBlank()) {
            throw new UnauthorizedException("当前用户未登录");
        }

        List<UserRecord> openRecords = userRecordRepository.findOpenRecords(userName, deviceId);
        if (openRecords.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "当前没有待关闭的观看记录");
        }

        UserRecord record = openRecords.get(0);
        record.setEnd_time(LocalDateTime.now());
        userRecordRepository.save(record);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("code", "OK");
        result.put("message", "观看记录已结束");
        return ResponseEntity.ok(result);
    }

    private void ensureOpenRecord(String userName, String deviceId) {
        List<UserRecord> openRecords = userRecordRepository.findOpenRecords(userName, deviceId);
        if (!openRecords.isEmpty()) {
            return;
        }

        UserRecord record = new UserRecord();
        record.setUserName(userName);
        record.setDjiId(deviceId);
        record.setStart_time(LocalDateTime.now());
        userRecordRepository.save(record);
        log.info("记录用户直播观看记录: userName={}, deviceId={}", userName, deviceId);
    }

    private void throwStartLiveFailure(WsCommandAckResult ackResult) {
        if (ApiErrorCode.LIVE_REQUEST_SEND_FAILED.getCode().equals(ackResult.getCode())) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.LIVE_REQUEST_SEND_FAILED, ackResult.getMessage());
        }
        if (ApiErrorCode.UAV_NOT_CONNECTED.getCode().equals(ackResult.getCode())) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.UAV_NOT_CONNECTED, ackResult.getMessage());
        }
        throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.LIVE_START_REJECTED, ackResult.getMessage());
    }

    private String buildWebSocketUrl(HttpServletRequest request, String deviceId) {
        String protocol = request.isSecure() ? "wss" : "ws";
        return protocol + "://" + request.getServerName() + ":" + request.getServerPort() + "/ws/web?deviceId=" + deviceId;
    }

}
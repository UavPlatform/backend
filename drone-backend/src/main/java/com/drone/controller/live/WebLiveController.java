package com.drone.controller.live;

import com.drone.mapper.UserRecordRepository;
import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.enums.ApiErrorCode;
import com.drone.pojo.result.Result;
import com.drone.service.impl.LiveSessionSnapshot;
import com.drone.server.annotation.OperationLog;
import com.drone.server.annotation.RateLimiter;
import com.drone.server.exception.BusinessException;
import com.drone.server.handler.DroneWebSocketHandler;
import com.drone.server.ws.handler.WsCommandAckResult;
import com.drone.server.util.LogMaskUtil;
import com.drone.server.util.UserContext;
import com.drone.service.AppWebSocketService;
import com.drone.service.LiveSessionService;
import com.drone.service.TRTCService;
import com.drone.service.WebUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
public class WebLiveController {

    private static final long LIVE_ACK_TIMEOUT_MILLIS = 5000L;
    private static final long LIVE_STARTING_TTL_MILLIS = 15000L;

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

    @OperationLog("请求开播")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(
            summary = "发送开播请求",
            description = "Web端向指定无人机发送开播请求",
            parameters = {
                    @Parameter(name = "deviceId", description = "无人机设备ID", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "发送成功 / 已运行 / 等待确认",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"data\": {\"roomId\": \"drone_xxx\", \"ackConfirmed\": true}}"))),
                    @ApiResponse(responseCode = "409", description = "设备未连接 / 正在启动中",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 409, \"errorCode\": \"UAV_NOT_CONNECTED\", \"message\": \"无人机未建立 WebSocket 连接\"}"))),
                    @ApiResponse(responseCode = "401", description = "未登录",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 401, \"errorCode\": \"UNAUTHORIZED\", \"message\": \"Missing token\"}"))),
                    @ApiResponse(responseCode = "429", description = "触发限流",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 429, \"errorCode\": \"RATE_LIMITED\", \"message\": \"请求过于频繁\"}")))
            }
    )
    @PostMapping("/req")
    public Result<Map<String, Object>> startLive(@RequestParam String deviceId) {
        String name = currentUserName();
        webUavService.getRegisteredUav(deviceId);

        if (!appWebSocketService.isConnected(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.UAV_NOT_CONNECTED);
        }

        LiveSessionSnapshot runningSnapshot = liveSessionService.getSnapshot(deviceId);
        if (runningSnapshot != null && liveSessionService.isRunning(deviceId)) {
            log.info("设备 {} 图传已在运行中", deviceId);
            Map<String, Object> data = new HashMap<>();
            data.put("code", ApiErrorCode.LIVE_ALREADY_RUNNING.getCode());
            data.put("ackConfirmed", true);
            data.put("liveState", runningSnapshot.getState().name());
            data.put("roomId", runningSnapshot.getRoomId());
            return Result.success("图传已在运行中", data);
        }

        if (liveSessionService.isStarting(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.LIVE_ALREADY_STARTING);
        }

        String roomId = trtcService.generateRoomId(deviceId);
        String userSig = trtcService.generateUserSig(deviceId);
        WsCommandAckResult ackResult = webSocketHandler.sendStartLiveCommand(
                deviceId, roomId, deviceId, userSig,
                LIVE_ACK_TIMEOUT_MILLIS, LIVE_STARTING_TTL_MILLIS
        );

        LiveSessionSnapshot snapshot = liveSessionService.getSnapshot(deviceId);

        Map<String, Object> data = new HashMap<>();
        data.put("requestId", ackResult.getRequestId());
        data.put("roomId", roomId);
        data.put("ackConfirmed", !ackResult.isTimedOut());
        data.put("liveState", snapshot != null ? snapshot.getState().name() : "IDLE");

        if (ackResult.isTimedOut()) {
            data.put("code", "LIVE_START_PENDING");
            return Result.success("开播命令已发送，等待设备确认", data);
        }

        if (!ackResult.isSuccess()) {
            throwStartLiveFailure(ackResult);
        }

        data.put("code", "LIVE_STARTED");
        return Result.success("设备已确认启动图传", data);
    }

    @OperationLog("获取拉流凭证")
    @Operation(
            summary = "获取拉流凭证",
            description = "Web端获取视频流的凭证",
            parameters = {
                    @Parameter(name = "deviceId", description = "无人机设备ID", required = true),
                    @Parameter(name = "webUserId", description = "Web端用户ID", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "获取成功",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"data\": {\"roomId\": \"drone_xxx\", \"userId\": \"web_xxx\", \"userSig\": \"...\", \"sdkAppId\": 1400000000}}"))),
                    @ApiResponse(responseCode = "409", description = "设备未连接",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 409, \"errorCode\": \"UAV_NOT_CONNECTED\", \"message\": \"无人机未建立 WebSocket 连接\"}")))
            }
    )
    @PostMapping("/get")
    public Result<Map<String, Object>> getPullCredentials(@RequestParam String deviceId,
                                                           @RequestParam String webUserId,
                                                           HttpServletRequest request) {
        webUavService.getRegisteredUav(deviceId);

        if (!appWebSocketService.isConnected(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.UAV_NOT_CONNECTED);
        }

        String roomId = trtcService.generateRoomId(deviceId);
        String userSig = trtcService.generateUserSig(webUserId);

        String userName = UserContext.getUsername();
        if (userName != null) {
            ensureOpenRecord(userName, deviceId);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("userId", webUserId);
        data.put("userSig", userSig);
        data.put("sdkAppId", trtcService.getSdkAppId());
        data.put("wsUrl", buildWebSocketUrl(request, deviceId));
        data.put("liveState", liveSessionService.getSnapshot(deviceId).getState().name());
        data.put("ackConfirmed", liveSessionService.isRunning(deviceId));

        return Result.success(data);
    }

    @OperationLog("结束观看")
    @Operation(
            summary = "结束观看会话",
            description = "当前用户退出图传观看时，补齐观看记录结束时间",
            parameters = {
                    @Parameter(name = "deviceId", description = "无人机设备ID", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "关闭成功",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"观看记录已结束\"}"))),
                    @ApiResponse(responseCode = "404", description = "无待关闭记录",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 404, \"errorCode\": \"INVALID_PARAM\", \"message\": \"当前没有待关闭的观看记录\"}")))
            }
    )
    @PostMapping("/close")
    public Result<Void> closeLive(@RequestParam String deviceId) {
        webUavService.getRegisteredUav(deviceId);
        String userName = currentUserName();

        List<UserRecord> openRecords = userRecordRepository.findOpenRecords(userName, deviceId);
        if (openRecords.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "当前没有待关闭的观看记录");
        }

        UserRecord record = openRecords.get(0);
        record.setEnd_time(LocalDateTime.now());
        userRecordRepository.save(record);

        log.info("观看记录已结束，用户: {}, 设备: {}", maskedName(), deviceId);
        return Result.success("观看记录已结束");
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
        log.info("创建观看记录，用户: {}, 设备: {}", maskedUserName(userName), deviceId);
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
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String protocol = "https".equalsIgnoreCase(forwardedProto) ? "wss" : "ws";
        if (forwardedProto == null) {
            protocol = request.isSecure() ? "wss" : "ws";
        }
        return protocol + "://" + request.getServerName() + ":" + request.getServerPort() + "/ws/web?deviceId=" + deviceId;
    }

    private String currentUserName() {
        return UserContext.getUsername();
    }

    private String maskedName() {
        return LogMaskUtil.maskUserName(currentUserName());
    }

    private String maskedUserName(String name) {
        return LogMaskUtil.maskUserName(name);
    }

}

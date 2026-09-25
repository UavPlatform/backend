package com.uav.live.controller;

import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.pojo.entity.UserRecord;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.result.Result;
import com.uav.uav.pojo.vo.LiveStartVO;
import com.uav.live.pojo.vo.PullCredentialsVO;
import com.uav.live.service.impl.LiveSessionSnapshot;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.annotation.RequireDrone;
import com.uav.server.annotation.RequireRole;
import com.uav.server.exception.BusinessException;
import com.uav.server.handler.DroneWebSocketHandler;
import com.uav.server.ws.handler.WsCommandAckResult;
import com.uav.server.util.LogMaskUtil;
import com.uav.server.util.UserContext;
import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.live.service.TRTCService;
import com.uav.uav.service.WebUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

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

    @Autowired
    private RiderUavRepository riderUavRepository;

    @OperationLog("请求开播")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(
            summary = "发送开播请求",
            description = "Web端向指定无人机发送开播请求",
            parameters = {
                    @Parameter(name = "deviceId", description = "无人机设备ID", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "发送成功 / 已运行 / 等待确认"),
                    @ApiResponse(responseCode = "409", description = "设备未连接 / 正在启动中"),
                    @ApiResponse(responseCode = "401", description = "未登录"),
                    @ApiResponse(responseCode = "429", description = "触发限流")
            }
    )
    @PostMapping("/req")
    public Result<LiveStartVO> startLive(@RequestParam String deviceId) {
        webUavService.getRegisteredUav(deviceId);
        return doStartLive(deviceId, LIVE_ACK_TIMEOUT_MILLIS, LIVE_STARTING_TTL_MILLIS);
    }

    /**
     * 飞手主动开播（1B-4b，裁决 Q3=C）：飞手仅可对「本人绑定（rider_uav）且在线」的设备开播。
     * 复用 START_LIVE 下发/ACK 与 /live/get 凭证链路，直播态并入 LiveSessionSnapshot，
     * 停止走 t12 的 STOP_LIVE/LIVE_STOPPED 链路（/live/close 对所有发起方生效）。
     */
    @RequireRole(1)
    @RequireDrone
    @OperationLog("飞手主动开播")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(
            summary = "飞手主动开播",
            description = "飞手对本人绑定且在线的设备发送开播请求（1B-4b）；设备归属校验 rider_uav",
            parameters = {
                    @Parameter(name = "deviceId", description = "无人机设备ID", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "发送成功 / 已运行 / 等待确认"),
                    @ApiResponse(responseCode = "403", description = "设备未绑定到当前飞手"),
                    @ApiResponse(responseCode = "404", description = "设备未注册"),
                    @ApiResponse(responseCode = "409", description = "设备未连接 / 正在启动中")
            }
    )
    @PostMapping("/rider/req")
    public Result<LiveStartVO> riderStartLive(@RequestParam String deviceId) {
        Long riderId = UserContext.getUserId();
        webUavService.getRegisteredUav(deviceId);
        if (riderId == null || !riderUavRepository.existsByUserIdAndDjiId(riderId, deviceId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "设备未绑定到当前飞手");
        }
        return doStartLive(deviceId, LIVE_ACK_TIMEOUT_MILLIS, LIVE_STARTING_TTL_MILLIS);
    }

    /**
     * 公共开播流程（运营端 /live/req 与飞手 /live/rider/req 共用）：
     * 在线检查 → 状态互斥 → 生成 roomId/userSig → START_LIVE 下发 + ACK 等待。
     */
    private Result<LiveStartVO> doStartLive(String deviceId, long ackTimeoutMillis, long startingTtlMillis) {
        if (!appWebSocketService.isConnected(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.UAV_NOT_CONNECTED);
        }

        LiveSessionSnapshot runningSnapshot = liveSessionService.getSnapshot(deviceId);
        if (runningSnapshot != null && liveSessionService.isRunning(deviceId)) {
            log.info("设备 {} 图传已在运行中", deviceId);
            return Result.success("图传已在运行中",
                    new LiveStartVO(null, runningSnapshot.getRoomId(), true,
                            runningSnapshot.getState().name(), ApiErrorCode.LIVE_ALREADY_RUNNING.getCode()));
        }

        if (liveSessionService.isStarting(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.LIVE_ALREADY_STARTING);
        }

        String roomId = trtcService.generateRoomId(deviceId);
        String userSig = trtcService.generateUserSig(deviceId);
        WsCommandAckResult ackResult = webSocketHandler.sendStartLiveCommand(
                deviceId, roomId, deviceId, userSig,
                ackTimeoutMillis, startingTtlMillis
        );

        LiveSessionSnapshot snapshot = liveSessionService.getSnapshot(deviceId);
        String liveState = snapshot != null ? snapshot.getState().name() : "IDLE";

        if (ackResult.isTimedOut()) {
            return Result.success("开播命令已发送，等待设备确认",
                    new LiveStartVO(ackResult.getRequestId(), roomId, false, liveState, "LIVE_START_PENDING"));
        }

        if (!ackResult.isSuccess()) {
            throwStartLiveFailure(ackResult);
        }

        return Result.success("设备已确认启动图传",
                new LiveStartVO(ackResult.getRequestId(), roomId, true, liveState, "LIVE_STARTED"));
    }

    @OperationLog("获取拉流凭证")
    @Operation(
            summary = "获取拉流凭证",
            description = "Web端获取视频流的凭证；TRTC 身份由服务端从登录态生成，客户端不可指定",
            parameters = {
                    @Parameter(name = "deviceId", description = "无人机设备ID", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "获取成功"),
                    @ApiResponse(responseCode = "409", description = "设备未连接")
            }
    )
    @PostMapping("/get")
    public Result<PullCredentialsVO> getPullCredentials(@RequestParam String deviceId,
                                                          HttpServletRequest request) {
        webUavService.getRegisteredUav(deviceId);

        if (!appWebSocketService.isConnected(deviceId)) {
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.UAV_NOT_CONNECTED);
        }

        // P0-9：TRTC 身份一律取当前登录用户，客户端传入的 webUserId 不再参与签名
        Long loginUserId = UserContext.getUserId();
        if (loginUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ApiErrorCode.INVALID_PARAM, "用户未登录");
        }
        String trtcUserId = String.valueOf(loginUserId);

        String roomId = trtcService.generateRoomId(deviceId);
        String userSig = trtcService.generateUserSig(trtcUserId);

        String userName = UserContext.getUsername();
        if (userName != null) {
            ensureOpenRecord(userName, deviceId);
        }

        PullCredentialsVO vo = new PullCredentialsVO(
                roomId, trtcUserId, userSig, trtcService.getSdkAppId(),
                buildWebSocketUrl(request, deviceId),
                liveSessionService.getSnapshot(deviceId).getState().name(),
                liveSessionService.isRunning(deviceId)
        );
        return Result.success(vo);
    }

    @OperationLog("结束观看/停止推流")
    @Operation(
            summary = "结束观看并请求停止推流（1A-5a）",
            description = "运营端结束观看语义：先补齐当前用户观看记录 end_time；若设备在线且直播在运行，"
                    + "则向设备下发 STOP_LIVE 命令（复用 START_LIVE 的 ACK/超时模式），确认后结束直播会话"
                    + "并补齐该设备全体观众的观看记录；设备离线则直接补终态。"
                    + "协议详见 DroneWebSocketHandler#sendStopLiveCommand javadoc。",
            parameters = {
                    @Parameter(name = "deviceId", description = "无人机设备ID", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "已停止 / 等待设备确认 / 离线补终态"),
                    @ApiResponse(responseCode = "404", description = "设备未注册"),
                    @ApiResponse(responseCode = "409", description = "设备拒绝停止推流（LIVE_STOP_REJECTED）")
            }
    )
    @PostMapping("/close")
    public Result<Void> closeLive(@RequestParam String deviceId) {
        webUavService.getRegisteredUav(deviceId);
        String userName = currentUserName();

        // 1. 发起方本人不再观看：先补齐其观看记录 end_time（既有语义保留，任何分支都生效）
        closeCallerOpenRecord(userName, deviceId);

        // 2. 运营端结束观看 = 停止推流（1A-5a 新语义）
        if (!appWebSocketService.isConnected(deviceId)) {
            // 设备离线：直播必然已断（WS close 钩子已 markStopped），补齐终态即可
            liveSessionService.markStopped(deviceId);
            closeAllViewerRecords(deviceId, "设备离线");
            log.info("观看记录已结束，用户: {}, 设备: {}（设备离线，直播已结束）", maskedName(), deviceId);
            return Result.success("无人机已离线，直播已结束");
        }
        if (!liveSessionService.isRunning(deviceId) && !liveSessionService.isStarting(deviceId)) {
            closeAllViewerRecords(deviceId, "直播未在运行");
            log.info("观看记录已结束，用户: {}, 设备: {}（直播未在运行）", maskedName(), deviceId);
            return Result.success("直播未在运行，观看记录已结束");
        }

        WsCommandAckResult ack = webSocketHandler.sendStopLiveCommand(deviceId, LIVE_ACK_TIMEOUT_MILLIS);
        if (ack.isSuccess()) {
            liveSessionService.markStopped(deviceId);
            closeAllViewerRecords(deviceId, "设备确认停止");
            log.info("设备 {} 已确认停止推流，观看记录已全部结束", deviceId);
            return Result.success("设备已确认停止推流");
        }
        if (ack.isTimedOut()) {
            // 不确定设备是否已停：保持状态由设备异步上报 LIVE_STOPPED 事件兜底（LiveEventHandler）
            log.warn("设备 {} 停止命令 ACK 超时，等待 LIVE_STOPPED 事件兜底", deviceId);
            return Result.success("停止命令已发送，等待设备确认");
        }
        throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.LIVE_STOP_REJECTED, ack.getMessage());
    }

    @OperationLog("退出观看")
    @Operation(
            summary = "退出观看（1B-4b 微端点）",
            description = "仅结束当前登录用户在该设备的未关闭观看记录（end_time=now），"
                    + "完全不动推流与直播状态；幂等（无开放记录也返回 200）。"
                    + "与停止链路（LIVE_STOPPED 统一补齐）叠加后，正常退出与异常退出口径完整。",
            parameters = {
                    @Parameter(name = "deviceId", description = "无人机设备ID", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "观看记录已结束（含幂等场景）"),
                    @ApiResponse(responseCode = "404", description = "设备未注册")
            }
    )
    @PostMapping("/leave")
    public Result<Void> leaveLive(@RequestParam String deviceId) {
        webUavService.getRegisteredUav(deviceId);
        String userName = currentUserName();
        // 仅收口本人的观看记录；LiveSession 与推流完全不受影响（区别于 /live/close）
        closeCallerOpenRecord(userName, deviceId);
        log.info("用户 {} 退出观看，观看记录已结束，设备: {}", maskedName(), deviceId);
        return Result.success("观看记录已结束");
    }

    private void closeCallerOpenRecord(String userName, String deviceId) {
        List<UserRecord> openRecords = userRecordRepository.findOpenRecords(userName, deviceId);
        for (UserRecord record : openRecords) {
            record.setEnd_time(LocalDateTime.now());
            userRecordRepository.save(record);
        }
    }

    private void closeAllViewerRecords(String deviceId, String reason) {
        List<UserRecord> openRecords = userRecordRepository.findOpenByDeviceId(deviceId);
        for (UserRecord record : openRecords) {
            record.setEnd_time(LocalDateTime.now());
            userRecordRepository.save(record);
        }
        if (!openRecords.isEmpty()) {
            log.info("设备 {} 观看记录已统一结束（{} 条，原因: {}）", deviceId, openRecords.size(), reason);
        }
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

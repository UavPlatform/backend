package com.uav.live.controller;

import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.pojo.entity.UserRecord;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.result.Result;
import com.uav.live.pojo.vo.PullCredentialsVO;
import com.uav.server.annotation.OperationLog;
import com.uav.server.exception.BusinessException;
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
import jakarta.servlet.http.HttpServletResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备级图传只读入口（ADR-0004 / ADR-0014）。
 *
 * <p>服务器不再遥控设备开停播，也不再维持设备长连接：开播由飞手在本机发起，
 * 订单级直播状态与观看票据走 {@code /dispatch/orders/{orderId}/live}。
 * 本控制器只保留已发布的「取拉流凭证」与「收口本人观看记录」两个端点。
 */
@Tag(name = "Web Live API")
@RestController
@RequestMapping("/live")
@Slf4j
public class WebLiveController {

    @Autowired
    private TRTCService trtcService;
    @Autowired
    private UserRecordRepository userRecordRepository;
    @Autowired
    private WebUavService webUavService;
    @Autowired
    private AppWebSocketService appWebSocketService;
    @Autowired
    private LiveSessionService liveSessionService;

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
                                                          HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
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
                liveSessionService.isRunning(deviceId),
                trtcService.generatePrivateMapKey(trtcUserId, roomId, false)
        );
        return Result.success(vo);
    }

    @OperationLog("退出观看")
    @Operation(
            summary = "退出观看（1B-4b 微端点）",
            description = "仅结束当前登录用户在该设备的未关闭观看记录（end_time=now），"
                    + "完全不动推流与直播状态；幂等（无开放记录也返回 200）。",
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
        // 仅收口本人的观看记录；LiveSession 与推流完全不受影响（区别于已删除的 /live/close）
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

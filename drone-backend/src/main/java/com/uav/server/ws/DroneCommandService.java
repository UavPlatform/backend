package com.uav.server.ws;

import com.alibaba.fastjson.JSON;
import com.uav.live.pojo.dto.WsEnvelope;
import com.uav.live.service.LiveSessionService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.ws.handler.WsCommandAckResult;
import com.uav.server.ws.handler.WsCommandResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends commands to connected devices and waits for their acknowledgements.
 */
@Component
@Slf4j
public class DroneCommandService {

    private final DroneSessionRegistry sessionRegistry;
    private final WsCommandResponseHandler commandResponseHandler;
    private final LiveSessionService liveSessionService;

    public DroneCommandService(DroneSessionRegistry sessionRegistry,
                               WsCommandResponseHandler commandResponseHandler,
                               LiveSessionService liveSessionService) {
        this.sessionRegistry = sessionRegistry;
        this.commandResponseHandler = commandResponseHandler;
        this.liveSessionService = liveSessionService;
    }

    public WsCommandAckResult sendCommandWithAck(String deviceId, String commandName,
                                                 Map<String, Object> data, long ackTimeoutMillis) {
        if (!sessionRegistry.isConnected(deviceId)) {
            return offlineResult();
        }

        String requestId = UUID.randomUUID().toString();
        WsEnvelope payload = commandEnvelope(requestId, commandName, deviceId, data);
        CompletableFuture<WsCommandAckResult> future = new CompletableFuture<>();
        commandResponseHandler.addPendingCommand(requestId, new WsCommandResponseHandler.PendingCommand(
                deviceId, commandName, null, System.currentTimeMillis() + ackTimeoutMillis, future));

        if (!sessionRegistry.send(deviceId, JSON.toJSONString(payload))) {
            commandResponseHandler.removePendingCommand(requestId);
            return sendFailed(requestId, "指令发送失败，设备可能已断开");
        }
        return awaitAck(requestId, future, ackTimeoutMillis, "设备未在超时时间内确认指令");
    }

    public WsCommandAckResult sendStartLiveCommand(String deviceId, String roomId, String userId,
                                                   String userSig, long ackTimeoutMillis, long startingTtlMillis) {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> data = new ConcurrentHashMap<>();
        data.put("roomId", roomId);
        data.put("userId", userId);
        data.put("userSig", userSig);
        WsEnvelope payload = commandEnvelope(requestId, WsMessageNames.START_LIVE, deviceId, data);

        CompletableFuture<WsCommandAckResult> future = new CompletableFuture<>();
        commandResponseHandler.addPendingCommand(requestId, new WsCommandResponseHandler.PendingCommand(
                deviceId, WsMessageNames.START_LIVE, roomId,
                System.currentTimeMillis() + startingTtlMillis, future));
        liveSessionService.markStarting(deviceId, roomId, requestId, startingTtlMillis);

        if (!sessionRegistry.send(deviceId, JSON.toJSONString(payload))) {
            commandResponseHandler.removePendingCommand(requestId);
            liveSessionService.markFailed(deviceId);
            WsCommandAckResult failed = new WsCommandAckResult();
            failed.setRequestId(requestId);
            failed.setSuccess(false);
            failed.setCode(ApiErrorCode.LIVE_REQUEST_SEND_FAILED.getCode());
            failed.setMessage(ApiErrorCode.LIVE_REQUEST_SEND_FAILED.getDefaultMessage());
            return failed;
        }
        return awaitAck(requestId, future, ackTimeoutMillis, ApiErrorCode.LIVE_ACK_TIMEOUT.getDefaultMessage());
    }

    /**
     * Sends STOP_LIVE and waits for an acknowledgement.
     *
     * <p>Timeouts are backstopped by the asynchronous {@code LIVE_STOPPED} event
     * handled by {@code LiveEventHandler}.
     */
    public WsCommandAckResult sendStopLiveCommand(String deviceId, long ackTimeoutMillis) {
        if (!sessionRegistry.isConnected(deviceId)) {
            return offlineResult();
        }

        String requestId = UUID.randomUUID().toString();
        WsEnvelope payload = commandEnvelope(requestId, WsMessageNames.STOP_LIVE, deviceId, Map.of());
        CompletableFuture<WsCommandAckResult> future = new CompletableFuture<>();
        commandResponseHandler.addPendingCommand(requestId, new WsCommandResponseHandler.PendingCommand(
                deviceId, WsMessageNames.STOP_LIVE, null, System.currentTimeMillis() + ackTimeoutMillis, future));

        if (!sessionRegistry.send(deviceId, JSON.toJSONString(payload))) {
            commandResponseHandler.removePendingCommand(requestId);
            WsCommandAckResult failed = new WsCommandAckResult();
            failed.setRequestId(requestId);
            failed.setSuccess(false);
            failed.setCode(ApiErrorCode.LIVE_REQUEST_SEND_FAILED.getCode());
            failed.setMessage(ApiErrorCode.LIVE_REQUEST_SEND_FAILED.getDefaultMessage());
            return failed;
        }
        return awaitAck(requestId, future, ackTimeoutMillis, ApiErrorCode.LIVE_ACK_TIMEOUT.getDefaultMessage());
    }

    private WsEnvelope commandEnvelope(String requestId, String commandName, String deviceId,
                                       Map<String, Object> data) {
        WsEnvelope payload = new WsEnvelope();
        payload.setId(requestId);
        payload.setType(WsMessageNames.TYPE_COMMAND);
        payload.setName(commandName);
        payload.setDeviceId(deviceId);
        payload.setTimestamp(System.currentTimeMillis());
        payload.setData(data);
        return payload;
    }

    private WsCommandAckResult awaitAck(String requestId, CompletableFuture<WsCommandAckResult> future,
                                        long timeoutMillis, String timeoutMessage) {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            // Keep the pending command: a late ack still updates the live-session state.
            WsCommandAckResult timeoutResult = new WsCommandAckResult();
            timeoutResult.setRequestId(requestId);
            timeoutResult.setSuccess(false);
            timeoutResult.setTimedOut(true);
            timeoutResult.setCode(ApiErrorCode.LIVE_ACK_TIMEOUT.getCode());
            timeoutResult.setMessage(timeoutMessage);
            return timeoutResult;
        } catch (Exception error) {
            commandResponseHandler.removePendingCommand(requestId);
            WsCommandAckResult failed = new WsCommandAckResult();
            failed.setRequestId(requestId);
            failed.setSuccess(false);
            failed.setCode(ApiErrorCode.INTERNAL_ERROR.getCode());
            failed.setMessage("等待设备确认失败");
            return failed;
        } finally {
            if (future.isDone()) {
                commandResponseHandler.removePendingCommand(requestId);
            }
        }
    }

    private WsCommandAckResult offlineResult() {
        WsCommandAckResult offline = new WsCommandAckResult();
        offline.setRequestId(null);
        offline.setSuccess(false);
        offline.setCode(ApiErrorCode.UAV_NOT_CONNECTED.getCode());
        offline.setMessage(ApiErrorCode.UAV_NOT_CONNECTED.getDefaultMessage());
        return offline;
    }

    private WsCommandAckResult sendFailed(String requestId, String message) {
        WsCommandAckResult failed = new WsCommandAckResult();
        failed.setRequestId(requestId);
        failed.setSuccess(false);
        failed.setCode(ApiErrorCode.UAV_NOT_CONNECTED.getCode());
        failed.setMessage(message);
        return failed;
    }
}

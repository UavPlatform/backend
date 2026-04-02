package com.drone.server.ws.handler;

import com.alibaba.fastjson.JSONObject;
import com.drone.server.exception.ApiErrorCode;
import com.drone.server.ws.service.WsMessageService;
import com.drone.service.LiveSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class WsCommandResponseHandler implements WsMessageHandler {

    @Autowired
    private LiveSessionService liveSessionService;

    @Autowired
    private WsMessageService messageService;

    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    @Override
    public String getType() {
        return "response";
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public boolean supports(String type, String name) {
        return "response".equalsIgnoreCase(type) || "error".equalsIgnoreCase(type);
    }

    @Override
    public void handle(JSONObject json, WebSocketSession session) {
        String requestId = firstNonBlank(json.getString("replyTo"), json.getString("requestId"));
        if (requestId == null || requestId.isBlank()) {
            messageService.sendError(session, ApiErrorCode.INVALID_MESSAGE, "响应消息缺少 replyTo");
            return;
        }

        PendingCommand pendingCommand = pendingCommands.remove(requestId);
        if (pendingCommand == null) {
            log.info("收到未知命令应答，requestId={}", requestId);
            return;
        }

        WsCommandAckResult ackResult = new WsCommandAckResult();
        ackResult.setRequestId(requestId);
        ackResult.setSuccess(resolveAckSuccess(json));
        ackResult.setCode(firstNonBlank(
                json.getString("code"),
                ackResult.isSuccess() ? "OK" : ApiErrorCode.LIVE_START_REJECTED.getCode()
        ));
        ackResult.setMessage(firstNonBlank(
                json.getString("message"),
                ackResult.isSuccess() ? "设备已确认命令" : "设备拒绝执行命令"
        ));
        pendingCommand.future.complete(ackResult);

        if ("START_LIVE".equalsIgnoreCase(pendingCommand.commandName)) {
            if (ackResult.isSuccess()) {
                liveSessionService.markRunning(pendingCommand.deviceId, pendingCommand.roomId, requestId);
            } else {
                liveSessionService.markFailed(pendingCommand.deviceId);
            }
        }
    }

    public void addPendingCommand(String requestId, PendingCommand command) {
        pendingCommands.put(requestId, command);
    }

    public PendingCommand removePendingCommand(String requestId) {
        return pendingCommands.remove(requestId);
    }

    public void failPendingCommands(String deviceId) {
        pendingCommands.entrySet().removeIf(entry -> {
            PendingCommand pendingCommand = entry.getValue();
            if (!pendingCommand.deviceId.equals(deviceId)) {
                return false;
            }
            WsCommandAckResult failed = new WsCommandAckResult();
            failed.setRequestId(entry.getKey());
            failed.setSuccess(false);
            failed.setCode(ApiErrorCode.UAV_NOT_CONNECTED.getCode());
            failed.setMessage(ApiErrorCode.UAV_NOT_CONNECTED.getDefaultMessage());
            pendingCommand.future.complete(failed);
            return true;
        });
    }

    private boolean resolveAckSuccess(JSONObject json) {
        Boolean success = json.getBoolean("success");
        if (success != null) {
            return success;
        }
        return !"error".equalsIgnoreCase(json.getString("type"));
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    public static class PendingCommand {
        private final String deviceId;
        private final String commandName;
        private final String roomId;
        private final long expiresAt;
        private final CompletableFuture<WsCommandAckResult> future;

        public PendingCommand(String deviceId, String commandName, String roomId, long expiresAt, CompletableFuture<WsCommandAckResult> future) {
            this.deviceId = deviceId;
            this.commandName = commandName;
            this.roomId = roomId;
            this.expiresAt = expiresAt;
            this.future = future;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getCommandName() {
            return commandName;
        }

        public String getRoomId() {
            return roomId;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public CompletableFuture<WsCommandAckResult> getFuture() {
            return future;
        }
    }
}

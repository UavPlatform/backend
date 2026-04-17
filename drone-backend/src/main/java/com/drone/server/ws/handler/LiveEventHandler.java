package com.drone.server.ws.handler;

import com.alibaba.fastjson.JSONObject;
import com.drone.server.ws.service.WsMessageService;
import com.drone.service.LiveSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@Slf4j
public class LiveEventHandler implements WsMessageHandler {

    @Autowired
    private LiveSessionService liveSessionService;

    @Autowired
    private WsMessageService messageService;

    @Override
    public String getType() {
        return "event";
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public boolean supports(String type, String name) {
        return "event".equalsIgnoreCase(type) && 
               ("LIVE_STARTED".equalsIgnoreCase(name) || "LIVE_STOPPED".equalsIgnoreCase(name));
    }

    @Override
    public void handle(JSONObject json, WebSocketSession session) {
        String eventName = json.getString("name");
        if (eventName == null) {
            return;
        }

        String deviceId = WsSessionDeviceIdResolver.resolve(session, json);
        if (deviceId == null) {
            log.warn("处理 {} 时未解析到 deviceId", eventName);
            return;
        }

        if ("LIVE_STARTED".equalsIgnoreCase(eventName)) {
            handleLiveStarted(json, deviceId);
        } else if ("LIVE_STOPPED".equalsIgnoreCase(eventName)) {
            handleLiveStopped(deviceId);
        }
    }

    private void handleLiveStarted(JSONObject json, String deviceId) {
        JSONObject data = extractData(json);
        String roomId = data != null ? data.getString("roomId") : json.getString("roomId");
        String requestId = firstNonBlank(json.getString("replyTo"), json.getString("requestId"));
        liveSessionService.markRunning(deviceId, roomId, requestId);
        log.info("设备 {} 直播已开始, roomId: {}", deviceId, roomId);
    }

    private void handleLiveStopped(String deviceId) {
        liveSessionService.markStopped(deviceId);
        log.info("设备 {} 直播已停止", deviceId);
    }

    private JSONObject extractData(JSONObject json) {
        JSONObject data = json.getJSONObject("data");
        if (data != null) {
            return data;
        }
        return json.containsKey("roomId") ? json : null;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}

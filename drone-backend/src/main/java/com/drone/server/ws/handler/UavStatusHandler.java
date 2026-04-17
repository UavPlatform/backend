package com.drone.server.ws.handler;

import com.alibaba.fastjson.JSONObject;
import com.drone.pojo.dto.UavStatusDto;
import com.drone.pojo.dto.WsEnvelope;
import com.drone.server.exception.ApiErrorCode;
import com.drone.server.ws.service.WsMessageService;
import com.drone.server.ws.service.LiveWebSessionProvider;
import com.drone.server.ws.service.LiveWebSessionService;
import com.drone.service.UavStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@Slf4j
public class UavStatusHandler implements WsMessageHandler {

    @Autowired
    private UavStatusService uavStatusService;

    @Autowired
    private WsMessageService messageService;

    @Autowired
    private LiveWebSessionProvider liveWebSessionProvider;

    @Autowired
    private LiveWebSessionService liveWebSessionService;

    @Override
    public String getType() {
        return "event";
    }

    @Override
    public String getName() {
        return "UAV_STATUS";
    }

    @Override
    public void handle(JSONObject json, WebSocketSession session) {
        try {
            String deviceId = getDeviceId(session, json);
            if (deviceId == null) {
                log.warn("处理 UAV_STATUS 时未解析到 deviceId");
                return;
            }

            JSONObject data = extractData(json);
            if (data == null) {
                messageService.sendError(session, ApiErrorCode.INVALID_MESSAGE, "UAV_STATUS 缺少 data");
                return;
            }

            UavStatusDto status = data.toJavaObject(UavStatusDto.class);
            status.setDeviceId(deviceId);
            status.setReceivedAt(System.currentTimeMillis());
            uavStatusService.updateUavStatus(deviceId, status);

            forwardToLiveWebClient(deviceId, status);
            log.info("收到无人机 {} 的状态信息，deviceId={}, 操作：{}", status.getUavName(), deviceId, status.getOperation());
        } catch (Exception e) {
            log.warn("处理无人机状态消息失败: {}", e.getMessage());
            messageService.sendError(session, ApiErrorCode.INVALID_MESSAGE, "无人机状态消息格式错误");
        }
    }

    private void forwardToLiveWebClient(String deviceId, UavStatusDto status) {
        WebSocketSession webSession = liveWebSessionProvider.getSession(deviceId);
        if (webSession != null && webSession.isOpen()) {
            WsEnvelope envelope = new WsEnvelope();
            envelope.setType("event");
            envelope.setName("UAV_STATUS_UPDATE");
            envelope.setDeviceId(deviceId);
            envelope.setTimestamp(System.currentTimeMillis());
            envelope.setData(status);
            messageService.send(webSession, envelope);
            log.debug("已向设备 {} 的单设备 Web 客户端转发 UAV_STATUS_UPDATE", deviceId);
        }
        for (WebSocketSession dashboardSession : liveWebSessionService.getDashboardSessions()) {
            if (dashboardSession.isOpen()) {
                WsEnvelope envelope = new WsEnvelope();
                envelope.setType("event");
                envelope.setName("UAV_STATUS_UPDATE");
                envelope.setDeviceId(deviceId);
                envelope.setTimestamp(System.currentTimeMillis());
                envelope.setData(status);
                messageService.send(dashboardSession, envelope);
                log.debug("已向 Dashboard Web 客户端广播设备 {} 的 UAV_STATUS_UPDATE", deviceId);
            }
        }
    }

    private String getDeviceId(WebSocketSession session, JSONObject json) {
        return WsSessionDeviceIdResolver.resolve(session, json);
    }

    private JSONObject extractData(JSONObject json) {
        JSONObject data = json.getJSONObject("data");
        if (data != null) {
            return data;
        }
        return json.containsKey("uavId") ? json : null;
    }
}

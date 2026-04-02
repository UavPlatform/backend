package com.drone.server.ws.handler;

import com.alibaba.fastjson.JSONObject;
import com.drone.pojo.dto.UavStatusDto;
import com.drone.pojo.dto.WsEnvelope;
import com.drone.server.exception.ApiErrorCode;
import com.drone.server.ws.service.WsMessageService;
import com.drone.server.ws.service.LiveWebSessionProvider;
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
            String deviceId = getDeviceId(session);
            if (deviceId == null) {
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
            log.info("收到无人机 {} 的状态信息，操作：{}", status.getUavName(), status.getOperation());
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
        }
    }

    private String getDeviceId(WebSocketSession session) {
        return (String) session.getAttributes().get("deviceId");
    }

    private JSONObject extractData(JSONObject json) {
        JSONObject data = json.getJSONObject("data");
        if (data != null) {
            return data;
        }
        return json.containsKey("uavId") ? json : null;
    }
}

package com.drone.server.ws.handler;

import com.alibaba.fastjson.JSONObject;
import com.drone.pojo.dto.WsEnvelope;
import com.drone.server.ws.service.WsMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@Slf4j
public class PingHandler implements WsMessageHandler {

    @Autowired
    private WsMessageService messageService;

    @Override
    public String getType() {
        return "event";
    }

    @Override
    public String getName() {
        return "PING";
    }

    @Override
    public void handle(JSONObject json, WebSocketSession session) {
        String deviceId = getDeviceId(session);
        WsEnvelope pong = new WsEnvelope();
        pong.setType("event");
        pong.setName("PONG");
        pong.setReplyTo(json.getString("id"));
        pong.setDeviceId(deviceId);
        pong.setTimestamp(System.currentTimeMillis());
        pong.setSuccess(true);
        pong.setMessage("pong");
        messageService.send(session, pong);
    }

    private String getDeviceId(WebSocketSession session) {
        return (String) session.getAttributes().get("deviceId");
    }
}

package com.uav.server.ws.service;

import com.alibaba.fastjson.JSON;
import com.uav.live.pojo.dto.WsEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/** Serialized writes to a WebSocket session; Tomcat's basic remote rejects concurrent sends. */
@Component
@Slf4j
public class WsMessageService {

    public void send(WebSocketSession session, WsEnvelope envelope) {
        try {
            if (session != null && session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(JSON.toJSONString(envelope)));
                }
            }
        } catch (IOException e) {
            log.warn("发送 WebSocket 消息失败: {}", e.getMessage());
        }
    }
}

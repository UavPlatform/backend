package com.uav.server.ws.service;

import com.alibaba.fastjson.JSON;
import com.uav.live.pojo.dto.WsEnvelope;
import com.uav.server.enums.ApiErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

@Component
@Slf4j
public class WsMessageService {

    public void send(WebSocketSession session, WsEnvelope envelope) {
        try {
            if (session != null && session.isOpen()) {
                // Tomcat basic remote 不允许并发写；按 session 串行化（与 DroneWebSocketHandler 同一锁对象）
                synchronized (session) {
                    session.sendMessage(new TextMessage(JSON.toJSONString(envelope)));
                }
            }
        } catch (IOException e) {
            log.warn("发送 WebSocket 消息失败: {}", e.getMessage());
        }
    }

    public void sendError(WebSocketSession session, ApiErrorCode errorCode, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        WsEnvelope error = new WsEnvelope();
        error.setType("error");
        error.setName("ERROR");
        error.setTimestamp(System.currentTimeMillis());
        error.setSuccess(false);
        error.setCode(errorCode.getCode());
        error.setMessage(message);
        send(session, error);
    }

    public void sendText(WebSocketSession session, String message) {
        try {
            if (session != null && session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(message));
                }
            }
        } catch (IOException e) {
            log.warn("发送 WebSocket 文本消息失败: {}", e.getMessage());
        }
    }
}

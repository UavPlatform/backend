package com.drone.server.ws.handler;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class WsMessageRouter {

    private final List<WsMessageHandler> handlers;

    public WsMessageRouter(List<WsMessageHandler> handlerList) {
        this.handlers = handlerList;
    }

    public void route(JSONObject json, WebSocketSession session) {
        String type = json.getString("type");
        String name = json.getString("name");
        
        if (type == null || type.isBlank()) {
            log.warn("消息缺少 type 字段");
            return;
        }

        WsMessageHandler handler = handlers.stream()
                .filter(h -> h.supports(type, name))
                .findFirst()
                .orElse(null);

        if (handler != null) {
            handler.handle(json, session);
        } else {
            log.warn("未知消息: type={}, name={}", type, name);
        }
    }
}

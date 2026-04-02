package com.drone.server.ws.handler;

import com.alibaba.fastjson.JSONObject;
import org.springframework.web.socket.WebSocketSession;
// websocket消息处理器策略模式接口
public interface WsMessageHandler {
    String getType();
    
    String getName();
    
    default boolean supports(String type) {
        return getType().equalsIgnoreCase(type);
    }
    
    default boolean supports(String type, String name) {
        return supports(type) && (getName() == null || getName().equalsIgnoreCase(name));
    }
    
    void handle(JSONObject json, WebSocketSession session);
}

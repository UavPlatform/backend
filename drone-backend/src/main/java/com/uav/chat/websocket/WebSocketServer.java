package com.uav.chat.websocket;

import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.chat.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket服务
 */
@Component
@ServerEndpoint("/ws/{sid}")
public class WebSocketServer {

    //存放会话对象
    private static final Map<String, Session> sessionMap = new HashMap<>();

    @Autowired
    private SessionService sessionService;

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") Long userId) {
        System.out.println("客户端：" + userId + "建立连接");
        sessionMap.put(userId.toString(), session);
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param message 客户端发送过来的消息
     */
    @OnMessage
    public void onMessage(String message, @PathParam("userId") Long userId) {
        System.out.println("收到来自客户端：" + userId + "的信息:" + message);
    }

    /**
     * 连接关闭调用的方法
     *
     */
    @OnClose
    public void onClose( @PathParam("userId") Long userId) {
        System.out.println("连接断开:" + userId);
        sessionMap.remove(userId.toString());
    }

    /**
     * 群发
     *
     */
    public void sendToUserIds(MessageDTO dto) {
        List<Long> userIdsBySessionId = sessionService.getUserIdsBySessionId(dto.getSessionId());
        for (Long userId : userIdsBySessionId) {
            sendToUser(dto, userId);
        }
    }

    private void sendToUser(MessageDTO dto, Long userId) {
        Session session = sessionMap.get(userId.toString());
        if (session != null) {
            try {
                ChatEnvelope envelope = ChatEnvelope.builder()
                        .fromUserId(dto.getFromUserId())
                        .toUserId(userId)
                        .msgId(dto.getMsgId())
                        .isOffline(Boolean.FALSE)
                        .msgType(MsgType.CHAT)
                        .needAck(Boolean.FALSE)
                        .timestamp(dto.getCreateTime())  // 毫秒时间戳
                        .build();
                session.getBasicRemote().sendText(envelope.toString());
            } catch (Exception e) {
                // 发送失败的处理
                e.printStackTrace();
            }
        }
    }
}

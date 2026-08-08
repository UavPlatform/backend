package com.uav.chat.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.chat.service.MessageService;
import com.uav.chat.service.SessionService;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat 模块 WebSocket 服务
 * <p>
 * 路径: /ws/{sid}  其中 sid = 用户ID（数字）
 * 客户端连接示例: ws://host:8081/ws/2
 */
@Slf4j
@Component
@ServerEndpoint("/ws/{sid}")
public class WebSocketServer {

    private static final Map<Long, Session> sessionMap = new ConcurrentHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Jakarta WebSocket 实例不走 Spring 容器，必须从 SpringContextHolder 取
    private SessionService getSessionService() {
        return SpringContextHolder.getBean(SessionService.class);
    }

    private MessageService getMessageService() {
        return SpringContextHolder.getBean(MessageService.class);
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        Long userId;
        try {
            userId = Long.valueOf(sid);
        } catch (NumberFormatException e) {
            log.warn("WebSocket 连接 sid 非法（需要数字userId）: {}", sid);
            try { session.close(); } catch (Exception ignored) {}
            return;
        }

        sessionMap.put(userId, session);
        log.info("客户端 {} 建立 WebSocket 连接", userId);

        // 推送离线期间错过的消息（由各会话的最后读取时间决定）
        try {
            List<ChatEnvelope> offlineMessages = getMessageService().getUnreadMessages(userId);
            if (offlineMessages != null && !offlineMessages.isEmpty()) {
                for (ChatEnvelope envelope : offlineMessages) {
                    sendJson(session, envelope);
                }
                log.info("已向用户 {} 推送 {} 条离线消息", userId, offlineMessages.size());
            }
        } catch (Exception e) {
            log.error("推送离线消息失败, userId={}: {}", userId, e.getMessage());
        }
    }

    @OnMessage
    public void onMessage(String message, @PathParam("sid") String sid) {
        log.info("收到来自客户端 {} 的信息: {}", sid, message);
    }

    @OnClose
    public void onClose(@PathParam("sid") String sid) {
        Long userId;
        try {
            userId = Long.valueOf(sid);
        } catch (NumberFormatException e) {
            return;
        }
        sessionMap.remove(userId);
        log.info("客户端 {} 断开 WebSocket 连接", userId);
    }

    /**
     * 向会话中所有在线成员推送消息（发送者本人除外）
     */
    public void sendToUserIds(MessageDTO dto) {
        List<Long> userIds = getSessionService().getUserIdsBySessionId(dto.getSessionId());
        if (userIds == null) {
            return;
        }
        for (Long userId : userIds) {
            // 不推给自己
            if (userId.equals(dto.getFromUserId())) {
                continue;
            }
            sendToUser(dto, userId);
        }
    }

    private void sendToUser(MessageDTO dto, Long userId) {
        Session session = sessionMap.get(userId);
        if (session == null || !session.isOpen()) {
            return;  // 不在线，消息已落库，等用户上线后通过 sync 拉取
        }
        try {
            ChatEnvelope envelope = ChatEnvelope.builder()
                    .fromUserId(dto.getFromUserId())
                    .toUserId(userId)
                    .msgId(dto.getMsgId())
                    .sessionId(dto.getSessionId())
                    .isOffline(Boolean.FALSE)
                    .msgType(MsgType.CHAT)
                    .needAck(Boolean.FALSE)
                    .timestamp(dto.getCreateTime())
                    .payload(Map.of("text", dto.getContent()))
                    .build();
            sendJson(session, envelope);
        } catch (JsonProcessingException e) {
            log.error("序列化 ChatEnvelope 失败: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("向用户 {} 推送消息失败: {}", userId, e.getMessage());
        }
    }

    private void sendJson(Session session, Object obj) throws Exception {
        String json = objectMapper.writeValueAsString(obj);
        session.getBasicRemote().sendText(json);
    }
}

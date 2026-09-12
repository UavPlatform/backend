package com.uav.chat.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.chat.service.MessageService;
import com.uav.chat.service.SessionService;
import com.uav.server.ws.auth.WsAuthHandshakeInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat 模块 WebSocket 处理器（Spring WebSocket 栈）。
 *
 * <p>路径: /ws/{sid}，其中 sid = 用户ID（数字）。鉴权由 {@link WsAuthHandshakeInterceptor}
 * （CHAT 模式）在握手阶段完成：JWT 必填且令牌 userId 必须等于路径 sid。
 *
 * <p>历史背景：原实现为 Jakarta {@code @ServerEndpoint("/ws/{sid}")}。因 Tomcat 的
 * WsFilter 按 URI 模板抢占升级请求，/ws/{sid} 会遮蔽 /ws/drone、/ws/web 两个通道
 * （P0-1 修复中发现），故迁移到 Spring WebSocket 栈：精确路径 /ws/drone、/ws/web
 * 的优先级高于模板 /ws/{sid}，三者可共存，且与其它通道共用同一握手鉴权组件。
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 顶号关闭码（Q12=A 强制单设备在线，t35）：被「更新登录」顶掉的旧连接以此关闭，
     * 客户端（t28）识别后停止自动重连并提示「账号已在其他设备登录」。
     * 4000~4999 为 RFC6455 保留给应用的自定义段；仅聊天通道使用。
     */
    public static final CloseStatus CLOSE_REPLACED_BY_NEWER_LOGIN =
            new CloseStatus(4001, "replaced by newer login");

    private final Map<Long, WebSocketSession> sessionMap = new ConcurrentHashMap<>();

    private final ObjectProvider<MessageService> messageServiceProvider;

    private final SessionService sessionService;

    public ChatWebSocketHandler(ObjectProvider<MessageService> messageServiceProvider,
                                SessionService sessionService) {
        this.messageServiceProvider = messageServiceProvider;
        this.sessionService = sessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = (Long) session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID);
        if (userId == null) {
            log.warn("聊天 WebSocket 连接缺少已鉴权身份，拒绝");
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        WebSocketSession previous = sessionMap.put(userId, session);
        if (previous != null && previous.isOpen() && previous != session) {
            log.info("用户 {} 旧聊天连接被新连接替换（close 4001 replaced by newer login）", userId);
            try {
                previous.close(CLOSE_REPLACED_BY_NEWER_LOGIN);
            } catch (Exception ignored) {
            }
        }
        log.info("客户端 {} 建立 WebSocket 连接", userId);

        // 推送离线期间错过的消息（由各会话的最后读取时间决定）
        try {
            List<ChatEnvelope> offlineMessages = messageServiceProvider.getObject().getUnreadMessages(userId);
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

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.info("收到来自客户端 {} 的信息: {}",
                session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID);
        if (userId != null) {
            // 仅当仍是当前连接时移除，避免误删重连后的新连接
            sessionMap.remove(userId, session);
            log.info("客户端 {} 断开 WebSocket 连接", userId);
        }
    }

    /**
     * 向在线用户推送任意信封（1B-3 系统通知使用）；离线返回 false，由既有 sync 补偿。
     */
    public boolean pushToUser(Long userId, ChatEnvelope envelope) {
        WebSocketSession session = sessionMap.get(userId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            sendJson(session, envelope);
            return true;
        } catch (Exception e) {
            log.warn("向用户 {} 推送信封失败: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 向会话中所有在线成员推送消息（发送者本人除外）
     */
    public void sendToUserIds(MessageDTO dto) {
        List<Long> userIds = sessionService.getUserIdsBySessionId(dto.getSessionId());
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
        WebSocketSession session = sessionMap.get(userId);
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

    private void sendJson(WebSocketSession session, Object obj) throws Exception {
        String json = objectMapper.writeValueAsString(obj);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }
}

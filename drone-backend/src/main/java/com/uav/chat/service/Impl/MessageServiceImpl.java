package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import com.alibaba.fastjson.JSON;
import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.chat.repository.ChatMessageRepository;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.chat.service.MessageService;
import com.uav.chat.service.UserSessionService;
import com.uav.chat.websocket.ChatWebSocketHandler;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.user.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MessageServiceImpl implements MessageService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final UserSessionService userSessionService;
    private final UserService userService;
    private final ChatSessionRepository chatSessionRepository;

    public MessageServiceImpl(ChatMessageRepository chatMessageRepository,
                              ChatWebSocketHandler chatWebSocketHandler,
                              UserSessionService userSessionService,
                              UserService userService,
                              ChatSessionRepository chatSessionRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.userSessionService = userSessionService;
        this.userService = userService;
        this.chatSessionRepository = chatSessionRepository;
    }

    @Override
    public void sendMessage(@Valid MessageDTO dto) {
        Long userId = UserContext.getUserId();
        // 任务会话（task_num 非空）补成员校验：只有任务属主与应征/选定飞手（建会话时落的成员）可发言；
        // 非任务会话的存量 sendMessage 缺口不在本任务扩（见 TASK-BACKEND-005 汇报）
        ChatSession session = chatSessionRepository.findById(dto.getSessionId()).orElse(null);
        if (session != null && session.getTaskNum() != null
                && userSessionService.countBySessionIdAndUserId(session.getId(), userId) == 0) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "无权向该任务会话发送消息");
        }
        dto.setFromUserId(userId);
        dto.setCreateTime(System.currentTimeMillis());
        dto.setMsgId(createMsgId());

        try {
            chatWebSocketHandler.sendToUserIds(dto);
        } catch (Exception e) {
            log.warn("WebSocket 推送消息失败, msgId={}: {}", dto.getMsgId(), e.getMessage());
        }

        ChatMessage chatMessage = BeanUtil.copyProperties(dto, ChatMessage.class);
        chatMessageRepository.save(chatMessage);
    }

    private static String createMsgId() {
        return UUID.fastUUID().toString();
    }

    @Override
    @Transactional
    public void recallMessage(@Valid String messageId) {
        Long userId = UserContext.getUserId();
        ChatMessage message = chatMessageRepository.findByMsgId(messageId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.MESSAGE_NOT_FOUND));
        if (!Objects.equals(message.getFromUserId(), userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "只能撤回自己发送的消息");
        }
        if (message.getStatus() != null && message.getStatus() == 2) {
            return;
        }
        message.setStatus(2);
        message.setRecallTime(System.currentTimeMillis());
        chatMessageRepository.save(message);
    }

    @Override
    @Transactional
    public void deleteMessage(@Valid String messageId) {
        Long userId = UserContext.getUserId();
        ChatMessage message = chatMessageRepository.findByMsgId(messageId).orElse(null);
        if (message == null) {
            return;
        }

        List<Long> deletedByUserIds = message.getDeletedByUserIds();
        if (deletedByUserIds == null) {
            deletedByUserIds = new ArrayList<>();
            message.setDeletedByUserIds(deletedByUserIds);
        }
        if (!deletedByUserIds.contains(userId)) {
            deletedByUserIds.add(userId);
        }
        chatMessageRepository.save(message);
    }

    @Override
    public Object getMessages(Long sessionId) {
        Long userId = UserContext.getUserId();
        if (sessionId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "会话ID不能为空");
        }
        if (userSessionService.countBySessionIdAndUserId(sessionId, userId) == 0) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "无权查看该会话的消息");
        }

        List<ChatMessage> list = chatMessageRepository.findBySessionIdOrderByCreateTimeAsc(sessionId);
        list = list.stream()
                .filter(msg -> msg.getDeletedByUserIds() == null || !msg.getDeletedByUserIds().contains(userId))
                .toList();

        return list.stream().map(msg -> toEnvelope(msg, userId)).toList();
    }

    private ChatEnvelope toEnvelope(ChatMessage msg, Long userId) {
        MsgType type = msg.getMsgType() == null ? MsgType.CHAT : MsgType.fromCode(msg.getMsgType());
        Map<String, Object> payload = buildPayload(msg, userId, type);
        return ChatEnvelope.builder()
                .fromUserId(msg.getFromUserId())
                .toUserId(userId)
                .msgId(msg.getMsgId())
                .sessionId(msg.getSessionId())
                .isOffline(Boolean.TRUE)
                .msgType(type)
                .needAck(Boolean.FALSE)
                .timestamp(msg.getCreateTime())
                .payload(payload)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPayload(ChatMessage msg, Long userId, MsgType type) {
        String content = msg.getContent();
        if (type != MsgType.CHAT) {
            try {
                com.alibaba.fastjson.JSONObject parsed = JSON.parseObject(content);
                if (parsed != null) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("type", parsed.getString("type"));
                    payload.put("name", parsed.getString("name"));
                    payload.put("data", parsed.get("data") == null ? Map.of() : parsed.get("data"));
                    payload.put("text", parsed.getString("text") == null ? "" : parsed.getString("text"));
                    return payload;
                }
            } catch (Exception e) {
                log.warn("系统消息 content 解析失败，回退文本展示: {}", e.getMessage());
            }
            return Map.of("text", content == null ? "" : content);
        }
        String text = content == null ? "" : content;
        if (msg.getStatus() != null && msg.getStatus() == 2) {
            text = msg.getFromUserId().equals(userId)
                    ? "你撤回了一条消息"
                    : userService.getName(msg.getFromUserId()) + "撤回了一条消息";
        }
        return Map.of("text", text);
    }

    @Override
    public List<ChatEnvelope> getUnreadMessages(Long userId) {
        List<ChatUserSession> userSessions = userSessionService.findByUserId(userId);
        if (userSessions == null || userSessions.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatEnvelope> allUnread = new ArrayList<>();
        for (ChatUserSession us : userSessions) {
            Long lastReadTime = us.getLastReadTime();
            long since = lastReadTime != null ? lastReadTime : us.getJoinTime();

            List<ChatMessage> messages = chatMessageRepository
                    .findBySessionIdAndCreateTimeGreaterThanOrderByCreateTimeAsc(us.getSessionId(), since);
            for (ChatMessage msg : messages) {
                if (msg.getDeletedByUserIds() != null && msg.getDeletedByUserIds().contains(userId)) {
                    continue;
                }
                allUnread.add(toEnvelope(msg, userId));
            }
        }
        return allUnread;
    }

    @Override
    public Map<Long, Integer> getUnreadCountMap(Long userId) {
        List<ChatUserSession> userSessions = userSessionService.findByUserId(userId);
        if (userSessions == null || userSessions.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, Integer> countMap = new HashMap<>();
        for (ChatUserSession us : userSessions) {
            Long lastReadTime = us.getLastReadTime();
            long since = lastReadTime != null ? lastReadTime : us.getJoinTime();
            int count = chatMessageRepository.countUnread(us.getSessionId(), since, userId);
            countMap.put(us.getSessionId(), count);
        }
        return countMap;
    }

    @Override
    @Transactional
    public void markSessionRead(Long userId, Long sessionId) {
        if (userSessionService.countBySessionIdAndUserId(sessionId, userId) == 0) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "非会话成员");
        }
        userSessionService.updateLastReadTime(sessionId, userId, System.currentTimeMillis());
    }
}

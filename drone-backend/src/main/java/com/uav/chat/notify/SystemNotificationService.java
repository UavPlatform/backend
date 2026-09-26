package com.uav.chat.notify;

import com.alibaba.fastjson.JSON;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.repository.ChatMessageRepository;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.chat.repository.ChatUserSessionRepository;
import com.uav.chat.websocket.ChatWebSocketHandler;
import com.uav.server.notify.NotificationDraft;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.TaskAssignment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 系统通知派发（1B-3，裁决 Q9=A）。
 */
@Slf4j
@Service
public class SystemNotificationService {

    /** 系统通知会话类型标记（0=一对一，1=群组，2=系统通知） */
    public static final int SESSION_TYPE_SYSTEM = 2;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatUserSessionRepository chatUserSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final ObjectProvider<SystemNotificationService> self;

    public SystemNotificationService(ChatSessionRepository chatSessionRepository,
                                     ChatUserSessionRepository chatUserSessionRepository,
                                     ChatMessageRepository chatMessageRepository,
                                     ChatWebSocketHandler chatWebSocketHandler,
                                     TaskAssignmentRepository taskAssignmentRepository,
                                     ObjectProvider<SystemNotificationService> self) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatUserSessionRepository = chatUserSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.self = self;
    }

    public void dispatch(List<NotificationDraft> drafts) {
        for (NotificationDraft draft : drafts) {
            if (draft == null || draft.recipientId() == null) {
                continue;
            }
            try {
                self.getObject().insertNotificationInNewTx(draft);
            } catch (Exception e) {
                log.error("系统通知落库/推送失败, recipient={}, name={}: {}",
                        draft.recipientId(), draft.name(), e.getMessage(), e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertNotificationInNewTx(NotificationDraft draft) {
        insertNotification(draft);
    }

    private void insertNotification(NotificationDraft draft) {
        Long recipientId = resolveRecipient(draft);
        Long sessionId = ensureSystemSession(recipientId);
        long now = System.currentTimeMillis();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", draft.msgType().name());
        payload.put("name", draft.name());
        payload.put("data", draft.data() == null ? Map.of() : draft.data());
        payload.put("text", draft.text());

        ChatMessage message = ChatMessage.builder()
                .msgId(UUID.randomUUID().toString())
                .fromUserId(0L)
                .sessionId(sessionId)
                .content(JSON.toJSONString(payload))
                .status(0)
                .createTime(now)
                .msgType(draft.msgType().getCode())
                .deletedByUserIds(new ArrayList<>())
                .build();
        chatMessageRepository.save(message);

        ChatEnvelope envelope = ChatEnvelope.builder()
                .msgId(message.getMsgId())
                .sessionId(sessionId)
                .msgType(draft.msgType())
                .fromUserId(0L)
                .toUserId(recipientId)
                .isOffline(Boolean.FALSE)
                .needAck(Boolean.FALSE)
                .timestamp(now)
                .payload(payload)
                .build();
        boolean pushed = chatWebSocketHandler.pushToUser(recipientId, envelope);
        log.info("系统通知 [{}] 已落库并{}推送: recipient={}, sessionId={}",
                draft.name(), pushed ? "WS" : "未（离线，走 sync 补偿）", recipientId, sessionId);
    }

    private Long resolveRecipient(NotificationDraft draft) {
        if ("ORDER_CONFIRMED".equals(draft.name()) && draft.taskId() != null) {
            return taskAssignmentRepository.findByTaskId(draft.taskId())
                    .map(TaskAssignment::getRiderId)
                    .orElse(draft.recipientId());
        }
        return draft.recipientId();
    }

    private Long ensureSystemSession(Long userId) {
        List<ChatSession> found = chatSessionRepository.findByTypeAndOwnerIdOrderByIdAsc(
                SESSION_TYPE_SYSTEM, userId);
        if (found != null && !found.isEmpty()) {
            return found.get(0).getId();
        }
        long now = System.currentTimeMillis();
        ChatSession session = ChatSession.builder()
                .name("系统通知")
                .type(SESSION_TYPE_SYSTEM)
                .ownerId(userId)
                .userIds(List.of(userId))
                .createTime(now)
                .build();
        chatSessionRepository.saveAndFlush(session);
        chatUserSessionRepository.save(ChatUserSession.builder()
                .sessionId(session.getId())
                .userId(userId)
                .joinTime(now)
                .lastReadTime(0L)
                .build());
        log.info("已为用户 {} 创建系统通知会话 {}", userId, session.getId());
        return session.getId();
    }
}

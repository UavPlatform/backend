package com.uav.chat.notify;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.uav.chat.mapper.ChatMessageMapper;
import com.uav.chat.mapper.ChatSessionMapper;
import com.uav.chat.mapper.ChatUserSessionMapper;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.websocket.ChatWebSocketHandler;
import com.uav.server.notify.NotificationDraft;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.TaskAssignment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 系统通知派发（1B-3，裁决 Q9=A）。
 *
 * <p>职责：为接收方维护一个「系统通知」会话（chat_session.type=2，与普通聊天会话隔离），
 * 将状态变更通知以 ChatMessage（msg_type=1 NOTICE / 2 ORDER，content 为结构化 JSON）落库，
 * 并在接收方在线时经聊天 WS 实时推送；离线由既有 /chat/Message/sync 补偿（getUnreadMessages）。
 */
@Slf4j
@Service
public class SystemNotificationService {

    /** 系统通知会话类型标记（0=一对一，1=群组，2=系统通知） */
    public static final int SESSION_TYPE_SYSTEM = 2;

    private final ChatSessionMapper chatSessionMapper;

    private final ChatUserSessionMapper chatUserSessionMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final ChatWebSocketHandler chatWebSocketHandler;

    private final TaskAssignmentRepository taskAssignmentRepository;

    public SystemNotificationService(ChatSessionMapper chatSessionMapper,
                                     ChatUserSessionMapper chatUserSessionMapper,
                                     ChatMessageMapper chatMessageMapper,
                                     ChatWebSocketHandler chatWebSocketHandler,
                                     TaskAssignmentRepository taskAssignmentRepository) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatUserSessionMapper = chatUserSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.taskAssignmentRepository = taskAssignmentRepository;
    }

    /**
     * 派发一批通知（事务已提交后调用；单条失败不影响其余通知）。
     */
    public void dispatch(List<NotificationDraft> drafts) {
        for (NotificationDraft draft : drafts) {
            if (draft == null || draft.recipientId() == null) {
                continue;
            }
            try {
                insertNotification(draft);
            } catch (Exception e) {
                log.error("系统通知落库/推送失败, recipient={}, name={}: {}",
                        draft.recipientId(), draft.name(), e.getMessage(), e);
            }
        }
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
                .fromUserId(0L) // 0 = 系统
                .sessionId(sessionId)
                .content(JSON.toJSONString(payload))
                .status(0)
                .createTime(now)
                .msgType(draft.msgType().getCode())
                .deletedByUserIds(new ArrayList<>())
                .build();
        chatMessageMapper.insert(message);

        com.uav.chat.pojo.entity.ChatEnvelope envelope = com.uav.chat.pojo.entity.ChatEnvelope.builder()
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

    /**
     * 接收方解析：确认完成（ORDER_CONFIRMED）的对方是接单飞手（按 taskId 查接单记录），
     * 其余通知的接收方即草稿携带的用户（通常是任务/订单所有者本人或 actor 的对端）。
     */
    private Long resolveRecipient(NotificationDraft draft) {
        if ("ORDER_CONFIRMED".equals(draft.name()) && draft.taskId() != null) {
            return taskAssignmentRepository.findByTaskId(draft.taskId())
                    .map(TaskAssignment::getRiderId)
                    .orElse(draft.recipientId());
        }
        return draft.recipientId();
    }

    /**
     * 懒创建/复用接收方的「系统通知」会话；成员 lastReadTime=0，保证历史通知可被 sync 拉全。
     */
    private Long ensureSystemSession(Long userId) {
        List<ChatSession> found = chatSessionMapper.selectList(
                Wrappers.<ChatSession>lambdaQuery()
                        .eq(ChatSession::getType, SESSION_TYPE_SYSTEM)
                        .eq(ChatSession::getOwnerId, userId)
                        .orderByAsc(ChatSession::getId)
                        .last("LIMIT 1"));
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
        chatSessionMapper.insert(session);
        chatUserSessionMapper.insert(ChatUserSession.builder()
                .sessionId(session.getId())
                .userId(userId)
                .joinTime(now)
                .lastReadTime(0L)
                .build());
        log.info("已为用户 {} 创建系统通知会话 {}", userId, session.getId());
        return session.getId();
    }
}

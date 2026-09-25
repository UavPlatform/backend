package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.uav.chat.mapper.ChatMessageMapper;
import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.pojo.enums.MsgType;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements MessageService {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final UserSessionService userSessionService;
    private final UserService userService;

    public MessageServiceImpl(ChatWebSocketHandler chatWebSocketHandler, UserSessionService userSessionService, UserService userService) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.userSessionService = userSessionService;
        this.userService = userService;
    }

    @Override
    public void sendMessage(@Valid MessageDTO dto) {
        // fromUserId 以服务端 JWT 上下文为准
        dto.setFromUserId(UserContext.getUserId());
        dto.setCreateTime(System.currentTimeMillis());
        dto.setMsgId(createMsgId());

        // 先推 WS，追求低延迟（后续通过 MQ 解决消息丢失问题）
        try {
            chatWebSocketHandler.sendToUserIds(dto);
        } catch (Exception e) {
            log.warn("WebSocket 推送消息失败, msgId={}: {}", dto.getMsgId(), e.getMessage());
        }

        // 再持久化到数据库
        ChatMessage chatMessage = BeanUtil.copyProperties(dto, ChatMessage.class);
        super.save(chatMessage);
    }

    private static String createMsgId() {
        return UUID.fastUUID().toString();
    }

    @Override
    public void recallMessage(@Valid String messageId) {
        // P0-7：仅消息发送者本人可以撤回
        Long userId = UserContext.getUserId();
        ChatMessage message = baseMapper.selectOne(
                new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getMsgId, messageId));
        if (message == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.MESSAGE_NOT_FOUND);
        }
        if (!Objects.equals(message.getFromUserId(), userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "只能撤回自己发送的消息");
        }
        if (message.getStatus() != null && message.getStatus() == 2) {
            return; // 已撤回，幂等
        }

        LambdaUpdateWrapper<ChatMessage> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(ChatMessage::getMsgId, messageId)
                .set(ChatMessage::getStatus, 2)
                .set(ChatMessage::getRecallTime, System.currentTimeMillis());
        baseMapper.update(null, updateWrapper);
    }

    @Override
    public void deleteMessage(@Valid String messageId) {
        Long userId = UserContext.getUserId();

        // 按业务 msgId 查询
        LambdaQueryWrapper<ChatMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ChatMessage::getMsgId, messageId);
        ChatMessage message = baseMapper.selectOne(queryWrapper);
        if (message == null) {
            return;
        }

        List<Long> deletedByUserIds = message.getDeletedByUserIds();
        if (!deletedByUserIds.contains(userId)) {
            deletedByUserIds.add(userId);
        }

        // 使用 UpdateWrapper + 手动 JSON 序列化
        UpdateWrapper<ChatMessage> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("msg_id", messageId)
                .set("deleted_by_user_ids", JSON.toJSONString(deletedByUserIds));
        baseMapper.update(null, updateWrapper);
    }

    @Override
    public Object getMessages(Long sessionId) {
        Long userId = UserContext.getUserId();
        // P0-7：仅会话成员可以读取历史消息
        if (sessionId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "会话ID不能为空");
        }
        boolean isMember = userSessionService.count(Wrappers.<ChatUserSession>lambdaQuery()
                .eq(ChatUserSession::getSessionId, sessionId)
                .eq(ChatUserSession::getUserId, userId)) > 0;
        if (!isMember) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "无权查看该会话的消息");
        }

        LambdaQueryWrapper<ChatMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ChatMessage::getSessionId, sessionId);
        queryWrapper.orderByAsc(ChatMessage::getCreateTime);
        List<ChatMessage> list = super.list(queryWrapper);

        // 过滤软删除的消息（deleted_by_user_ids 老数据可能为 NULL，做空值防御）
        list = list.stream()
                .filter(msg -> msg.getDeletedByUserIds() == null || !msg.getDeletedByUserIds().contains(userId))
                .toList();

        return list.stream().map(msg -> toEnvelope(msg, userId)).toList();
    }

    /**
     * 统一信封映射：CHAT 消息 → {"text": ...}（含撤回文案改写）；
     * NOTICE/ORDER 系统消息 → 结构化载荷 {"type","name","data","text"}（1B-3）。
     */
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
        // 1. 查询该用户参与的所有会话关联
        List<ChatUserSession> userSessions = userSessionService.list(
                Wrappers.<ChatUserSession>lambdaQuery().eq(ChatUserSession::getUserId, userId));
        if (userSessions == null || userSessions.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatEnvelope> allUnread = new ArrayList<>();

        // 2. 遍历每个会话，拉取 lastReadTime 之后的消息
        //    P3-37 语义修复（t49）：本方法只做补拉，不再推进 lastReadTime——
        //    补推消息保留未读语义，已读由客户端进入会话后显式调用 markSessionRead 推进。
        for (ChatUserSession us : userSessions) {
            Long lastReadTime = us.getLastReadTime();
            long since = lastReadTime != null ? lastReadTime : us.getJoinTime();

            LambdaQueryWrapper<ChatMessage> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(ChatMessage::getSessionId, us.getSessionId())
                    .gt(ChatMessage::getCreateTime, since)
                    .orderByAsc(ChatMessage::getCreateTime);

            List<ChatMessage> messages = super.list(queryWrapper);
            for (ChatMessage msg : messages) {
                // 跳过已软删除的消息（deleted_by_user_ids 老数据可能为 NULL，做空值防御）
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
        List<ChatUserSession> userSessions = userSessionService.list(
                Wrappers.<ChatUserSession>lambdaQuery().eq(ChatUserSession::getUserId, userId));
        if (userSessions == null || userSessions.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, Integer> countMap = new HashMap<>();
        for (ChatUserSession us : userSessions) {
            Long lastReadTime = us.getLastReadTime();
            long since = lastReadTime != null ? lastReadTime : us.getJoinTime();
            int count = baseMapper.countUnread(us.getSessionId(), since, userId);
            countMap.put(us.getSessionId(), count);
        }
        return countMap;
    }

    @Override
    public void markSessionRead(Long userId, Long sessionId) {
        // t49：显式已读推进——仅会话成员可推进；非成员/未知会话一律 403
        boolean member = userSessionService.count(Wrappers.<ChatUserSession>lambdaQuery()
                .eq(ChatUserSession::getSessionId, sessionId)
                .eq(ChatUserSession::getUserId, userId)) > 0;
        if (!member) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "非会话成员");
        }
        userSessionService.update(Wrappers.<ChatUserSession>lambdaUpdate()
                .eq(ChatUserSession::getSessionId, sessionId)
                .eq(ChatUserSession::getUserId, userId)
                .set(ChatUserSession::getLastReadTime, System.currentTimeMillis()));
    }
}

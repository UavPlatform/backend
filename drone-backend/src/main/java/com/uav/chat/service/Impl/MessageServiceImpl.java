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
import com.uav.chat.websocket.WebSocketServer;
import com.uav.server.util.UserContext;
import com.uav.user.service.UserService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements MessageService {

    private final WebSocketServer webSocketServer;
    private final UserSessionService userSessionService;
    private final UserService userService;

    public MessageServiceImpl(WebSocketServer webSocketServer, UserSessionService userSessionService, UserService userService) {
        this.webSocketServer = webSocketServer;
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
            webSocketServer.sendToUserIds(dto);
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
        LambdaQueryWrapper<ChatMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ChatMessage::getSessionId, sessionId);
        queryWrapper.orderByAsc(ChatMessage::getCreateTime);
        List<ChatMessage> list = super.list(queryWrapper);

        // 过滤软删除的消息
        list = list.stream()
                .filter(msg -> !msg.getDeletedByUserIds().contains(userId))
                .toList();

        return list.stream().map(msg -> {
            ChatEnvelope envelope = ChatEnvelope.builder()
                    .fromUserId(msg.getFromUserId())
                    .toUserId(userId)
                    .msgId(msg.getMsgId())
                    .isOffline(true)
                    .msgType(MsgType.CHAT)
                    .needAck(Boolean.FALSE)
                    .timestamp(msg.getCreateTime())
                    .build();
            if (msg.getStatus() == 2) {
                String content = msg.getFromUserId().equals(userId)
                        ? "你撤回了一条消息"
                        : userService.getName(msg.getFromUserId()) + "撤回了一条消息";
                msg.setContent(content);
            }
            envelope.setPayload(Map.of("text", msg.getContent() != null ? msg.getContent() : ""));
            return envelope;
        }).toList();
    }

    @Override
    public List<ChatEnvelope> getUnreadMessages(Long userId) {
        // 1. 查询该用户参与的所有会话关联
        List<ChatUserSession> userSessions = userSessionService.list(
                Wrappers.<ChatUserSession>lambdaQuery().eq(ChatUserSession::getUserId, userId));
        if (userSessions == null || userSessions.isEmpty()) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        List<ChatEnvelope> allUnread = new ArrayList<>();

        // 2. 遍历每个会话，拉取 lastReadTime 之后的消息
        for (ChatUserSession us : userSessions) {
            Long lastReadTime = us.getLastReadTime();
            long since = lastReadTime != null ? lastReadTime : us.getJoinTime();

            LambdaQueryWrapper<ChatMessage> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(ChatMessage::getSessionId, us.getSessionId())
                    .gt(ChatMessage::getCreateTime, since)
                    .orderByAsc(ChatMessage::getCreateTime);

            List<ChatMessage> messages = super.list(queryWrapper);
            for (ChatMessage msg : messages) {
                // 跳过已软删除的消息
                if (msg.getDeletedByUserIds().contains(userId)) {
                    continue;
                }
                String content = msg.getContent();
                if (msg.getStatus() == 2) {
                    content = msg.getFromUserId().equals(userId)
                            ? "你撤回了一条消息"
                            : userService.getName(msg.getFromUserId()) + "撤回了一条消息";
                }
                ChatEnvelope envelope = ChatEnvelope.builder()
                        .fromUserId(msg.getFromUserId())
                        .toUserId(userId)
                        .msgId(msg.getMsgId())
                        .isOffline(true)  // 离线补推标记
                        .msgType(MsgType.CHAT)
                        .needAck(Boolean.FALSE)
                        .timestamp(msg.getCreateTime())
                        .payload(Map.of("text", content != null ? content : ""))
                        .build();
                allUnread.add(envelope);
            }

            // 3. 更新 lastReadTime
            ChatUserSession update = new ChatUserSession();
            update.setId(us.getId());
            update.setLastReadTime(now);
            userSessionService.updateById(update);
        }

        return allUnread;
    }
}

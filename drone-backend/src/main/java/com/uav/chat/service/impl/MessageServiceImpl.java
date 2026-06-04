package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.uav.chat.mapper.ChatMessageMapper;
import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.chat.service.MessageService;
import com.uav.chat.websocket.WebSocketServer;
import com.uav.server.util.UserContext;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements MessageService {
    private WebSocketServer webSocketServer;

    @Override
    public void sendMessage(@Valid MessageDTO dto) {
        dto.setCreateTime(System.currentTimeMillis());
        dto.setMsgId(CreateMsgId());
        // 尝试webSocket发送
        webSocketServer.sendToUserIds(dto);

        ChatMessage chatMessage = BeanUtil.copyProperties(dto, ChatMessage.class);
        super.save(chatMessage);
    }

    private static String CreateMsgId() {
        // UUID
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
        ChatMessage message = super.getById(messageId);
        List<Long> deletedByUserIds = message.getDeletedByUserIds();
        if (!deletedByUserIds.contains(userId)) {
            deletedByUserIds.add(userId);
        }
        super.updateById(message);
    }

    @Override
    public Object getMessages(Long sessionId) {
        Long userId = UserContext.getUserId();
        QueryWrapper<ChatMessage> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("session_id", sessionId);
        queryWrapper.orderByAsc("create_time");
        List<ChatMessage> list = super.list(queryWrapper);
        // 做内容处理,封装成 ChatEnvelope 再返回
        // 去掉删除的
        list = list.stream()
                .filter(msg -> !msg.getDeletedByUserIds().contains(userId))
                .toList();
        // 替换撤回的内容
        return list.stream().map(msg -> {
            ChatEnvelope envelope = ChatEnvelope.builder()
                    .fromUserId(msg.getFromUserId())
                    .toUserId(userId)
                    .msgId(msg.getMsgId())
                    .isOffline(true)
                    .msgType(MsgType.CHAT)
                    .needAck(Boolean.FALSE)
                    .timestamp(msg.getCreateTime())  // 毫秒时间戳
                    .build();
            if (msg.getStatus() == 2) {
                Long fromUserId = msg.getFromUserId();
                String content =  "";
                if (fromUserId.equals(userId)) {
                    content = "你撤回了一条消息";
                    msg.setContent(content);
                }
                else {
                    content = "id: " + fromUserId + "的用户撤回了一条消息";
                }
                msg.setContent(content);
                }
            envelope.setPayload(Map.of("text", msg.getContent()));
            return envelope;
        }).toList();
    }
}

package com.uav.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.entity.ChatMessage;
import jakarta.validation.Valid;

import java.util.List;

public interface MessageService extends IService<ChatMessage> {
    void sendMessage(@Valid MessageDTO dto);
    void recallMessage(@Valid String messageId);
    void deleteMessage(@Valid String messageId);
    Object getMessages(@Valid Long sessionId);

    /**
     * 获取用户所有会话的未读消息（自上次读取以来）
     * 调用后会自动更新 lastReadTime
     */
    List<ChatEnvelope> getUnreadMessages(Long userId);
}

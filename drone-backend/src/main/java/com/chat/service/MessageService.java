package com.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chat.pojo.dto.MessageDTO;
import com.chat.pojo.entity.ChatMessage;
import jakarta.validation.Valid;

public interface MessageService extends IService<ChatMessage>{
    void sendMessage(@Valid MessageDTO dto);
    void recallMessage(@Valid String messageId);
    void deleteMessage(@Valid String messageId);
    Object getMessages(@Valid Long sessionId);
}

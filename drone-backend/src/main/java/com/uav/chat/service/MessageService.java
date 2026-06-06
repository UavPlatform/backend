package com.uav.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.vo.MessageVO;

import java.util.List;

public interface MessageService extends IService<ChatMessage> {
    void sendMessage(MessageDTO dto);
    void recallMessage(Long messageId);
    void deleteMessage(Long messageId);
    List<MessageVO> getMessages(Long sessionId);
}

package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.uav.chat.mapper.ChatMessageMapper;
import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.vo.MessageVO;
import com.uav.chat.service.MessageService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements MessageService {

    @Override
    public void sendMessage(MessageDTO dto) {
        ChatMessage chatMessage = BeanUtil.copyProperties(dto, ChatMessage.class);
        chatMessage.setStatus(0);
        chatMessage.setCreateTime(LocalDateTime.now());
        super.save(chatMessage);
    }

    @Override
    public void recallMessage(Long messageId) {
        ChatMessage existing = super.getById(messageId);
        if (existing == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.MESSAGE_NOT_FOUND);
        }
        ChatMessage message = ChatMessage.builder()
                .id(messageId)
                .status(2)
                .recallTime(LocalDateTime.now())
                .build();
        super.updateById(message);
    }

    @Override
    public void deleteMessage(Long messageId) {
        Long userId = UserContext.getUserId();
        ChatMessage message = super.getById(messageId);
        if (message == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.MESSAGE_NOT_FOUND);
        }
        message.getDeletedByUserIds().add(userId);
        super.updateById(message);
    }

    @Override
    public List<MessageVO> getMessages(Long sessionId) {
        Long userId = UserContext.getUserId();
        QueryWrapper<ChatMessage> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("session_id", sessionId);
        queryWrapper.orderByAsc("create_time");
        List<ChatMessage> list = super.list(queryWrapper);

        list = list.stream()
                .filter(msg -> !msg.getDeletedByUserIds().contains(userId))
                .collect(Collectors.toList());

        return list.stream().map(msg -> {
            MessageVO vo = new MessageVO();
            BeanUtils.copyProperties(msg, vo);
            if (msg.getStatus() == 2) {
                vo.setContent("id: " + msg.getFromUserId() + "的用户撤回了一条消息");
            }
            return vo;
        }).collect(Collectors.toList());
    }
}

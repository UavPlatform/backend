package com.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chat.mapper.ChatMessageMapper;
import com.chat.pojo.dto.MessageDTO;
import com.chat.pojo.entity.ChatMessage;
import com.chat.pojo.vo.MessageVO;
import com.chat.service.MessageService;
import com.drone.server.util.UserContext;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
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
        ChatMessage message = ChatMessage.builder().id(messageId).status(2).recallTime(LocalDateTime.now()).build();
        super.updateById(message);
    }

    @Override
    public void deleteMessage(@Valid Long messageId) {
        Long userId = 2L;
        ChatMessage message = super.getById(messageId);
        message.getDeletedByUserIds().add(userId);
        super.updateById(message);
    }

    @Override
    public Object getMessages(Long sessionId) {
        Long userId = 2L;
        QueryWrapper<ChatMessage> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("session_id", sessionId);
        queryWrapper.orderByAsc("create_time");
        List<ChatMessage> list = super.list(queryWrapper);

        // 做内容处理,封装成 VO在返回
        // 去掉删除的
        list = list.stream()
                .filter(msg -> !msg.getDeletedByUserIds().contains(userId))
                .collect(Collectors.toList());
        // 替换撤回的内容
        return list.stream().map(msg -> {
            MessageVO vo = new MessageVO();
            BeanUtils.copyProperties(msg, vo);
            if (msg.getStatus() == 2) {
                Long fromUserId = vo.getFromUserId();
                String content = "id: " + fromUserId.toString() + "的用户撤回了一条消息";
                vo.setContent(content);
                return vo;
            }
            return vo;
        }).collect(Collectors.toList());
    }
}

package com.chat.service.Impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chat.mapper.ChatUserSessionMapper;
import com.chat.pojo.entity.ChatUserSession;
import com.chat.service.UserSessionService;
import org.springframework.stereotype.Service;

@Service
public class UserSessionServiceImpl extends ServiceImpl<ChatUserSessionMapper, ChatUserSession> implements UserSessionService {
}

package com.uav.chat.service.Impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.uav.chat.mapper.ChatUserSessionMapper;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.service.UserSessionService;
import org.springframework.stereotype.Service;

@Service
public class UserSessionServiceImpl extends ServiceImpl<ChatUserSessionMapper, ChatUserSession> implements UserSessionService {
}

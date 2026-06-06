package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.uav.chat.mapper.ChatSessionMapper;
import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.pojo.vo.SessionVO;
import com.uav.chat.service.SessionService;
import com.uav.chat.service.UserSessionService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements SessionService {

    @Autowired
    private UserSessionService userSessionService;

    @Override
    public void createSession(SessionDTO dto) {
        Long userId = UserContext.getUserId();
        List<Long> ids = dto.getUserIds();

        if (!ids.contains(userId)) {
            ids.add(userId);
        }
        List<Long> distinctIds = ids.stream().distinct().collect(Collectors.toList());

        List<ChatUserSession> sessions = distinctIds.stream()
                .map(id -> ChatUserSession.builder()
                        .sessionId(null)
                        .userId(id)
                        .joinTime(LocalDateTime.now())
                        .lastReadTime(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
        userSessionService.saveBatch(sessions);
    }

    @Override
    public void deleteSession(Long sessionId) {
        ChatSession session = super.getById(sessionId);
        if (session == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.SESSION_NOT_FOUND);
        }
        Long userId = UserContext.getUserId();
        if (session.getOwnerId().equals(userId)) {
            super.removeById(sessionId);
        }
    }

    @Override
    public List<SessionVO> listSession() {
        Long userId = UserContext.getUserId();

        LambdaQueryWrapper<ChatUserSession> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatUserSession::getUserId, userId);
        List<ChatUserSession> userSessions = userSessionService.list(wrapper);
        if (userSessions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sessionIds = userSessions.stream()
                .map(ChatUserSession::getSessionId)
                .collect(Collectors.toList());
        List<ChatSession> sessions = baseMapper.selectBatchIds(sessionIds);

        Map<Long, ChatSession> sessionMap = sessions.stream()
                .collect(Collectors.toMap(ChatSession::getId, Function.identity()));

        return userSessions.stream()
                .map(us -> {
                    ChatSession session = sessionMap.get(us.getSessionId());
                    if (session == null) return null;
                    return BeanUtil.copyProperties(session, SessionVO.class);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}

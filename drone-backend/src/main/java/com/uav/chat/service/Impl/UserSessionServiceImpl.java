package com.uav.chat.service.Impl;

import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.repository.ChatUserSessionRepository;
import com.uav.chat.service.UserSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserSessionServiceImpl implements UserSessionService {

    private final ChatUserSessionRepository chatUserSessionRepository;
    private final com.uav.chat.service.DispatchChatAccess dispatchAccess;

    public UserSessionServiceImpl(ChatUserSessionRepository chatUserSessionRepository, com.uav.chat.service.DispatchChatAccess dispatchAccess) {
        this.chatUserSessionRepository = chatUserSessionRepository;
        this.dispatchAccess = dispatchAccess;
    }

    @Override
    public List<ChatUserSession> findByUserId(Long userId) {
        return chatUserSessionRepository.findByUserId(userId).stream()
                .filter(s -> dispatchAccess.readable(s.getSessionId(), userId)).toList();
    }

    @Override
    public List<ChatUserSession> findBySessionId(Long sessionId) {
        return chatUserSessionRepository.findBySessionId(sessionId);
    }

    @Override
    public List<ChatUserSession> findBySessionIdIn(List<Long> sessionIds) {
        return chatUserSessionRepository.findBySessionIdIn(sessionIds);
    }

    @Override
    public long countBySessionIdAndUserId(Long sessionId, Long userId) {
        return dispatchAccess.readable(sessionId, userId) ? chatUserSessionRepository.countBySessionIdAndUserId(sessionId, userId) : 0;
    }

    @Override
    public ChatUserSession save(ChatUserSession session) {
        return chatUserSessionRepository.save(session);
    }

    @Override
    public void saveAll(List<ChatUserSession> sessions) {
        chatUserSessionRepository.saveAll(sessions);
    }

    @Override
    @Transactional
    public void updateLastReadTime(Long sessionId, Long userId, Long lastReadTime) {
        chatUserSessionRepository.updateLastReadTime(sessionId, userId, lastReadTime);
    }
}

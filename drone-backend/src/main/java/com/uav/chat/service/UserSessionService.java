package com.uav.chat.service;

import com.uav.chat.pojo.entity.ChatUserSession;

import java.util.List;

public interface UserSessionService {

    List<ChatUserSession> findByUserId(Long userId);

    List<ChatUserSession> findBySessionId(Long sessionId);

    List<ChatUserSession> findBySessionIdIn(List<Long> sessionIds);

    long countBySessionIdAndUserId(Long sessionId, Long userId);

    ChatUserSession save(ChatUserSession session);

    void saveAll(List<ChatUserSession> sessions);

    void updateLastReadTime(Long sessionId, Long userId, Long lastReadTime);
}

package com.uav.chat.repository;

import com.uav.chat.pojo.entity.ChatUserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatUserSessionRepository extends JpaRepository<ChatUserSession, Long> {

    List<ChatUserSession> findByUserId(Long userId);

    List<ChatUserSession> findBySessionId(Long sessionId);

    List<ChatUserSession> findBySessionIdIn(List<Long> sessionIds);

    Optional<ChatUserSession> findBySessionIdAndUserId(Long sessionId, Long userId);

    long countBySessionIdAndUserId(Long sessionId, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatUserSession c SET c.lastReadTime = :lastReadTime "
            + "WHERE c.sessionId = :sessionId AND c.userId = :userId")
    int updateLastReadTime(@Param("sessionId") Long sessionId,
                           @Param("userId") Long userId,
                           @Param("lastReadTime") Long lastReadTime);
}

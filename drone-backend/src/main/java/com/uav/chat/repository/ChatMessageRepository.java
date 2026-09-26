package com.uav.chat.repository;

import com.uav.chat.pojo.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findByMsgId(String msgId);

    List<ChatMessage> findBySessionIdOrderByCreateTimeAsc(Long sessionId);

    List<ChatMessage> findBySessionIdAndCreateTimeGreaterThanOrderByCreateTimeAsc(Long sessionId, Long createTime);

    Optional<ChatMessage> findFirstBySessionIdOrderByCreateTimeDesc(Long sessionId);

    /**
     * 未读数（t49）：deleted_by_user_ids 为 JSON 数组，用 LIKE 匹配实现跨 MySQL/H2 的成员排除。
     */
    @Query(value = """
            SELECT COUNT(*) FROM chat_messages
            WHERE session_id = :sessionId AND create_time > :since AND status != 2
            AND CONCAT(',', COALESCE(CAST(deleted_by_user_ids AS CHAR), '[]'), ',')
            NOT LIKE CONCAT('%,', :userId, ',%')
            """, nativeQuery = true)
    int countUnread(@Param("sessionId") Long sessionId,
                    @Param("since") long since,
                    @Param("userId") Long userId);
}

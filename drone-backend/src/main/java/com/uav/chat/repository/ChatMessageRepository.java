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
     * 未读数（t49）：
     * <ul>
     *   <li>只计他人发送的消息（{@code from_user_id <> userId}）：本人发出的消息对本人不是「未读」；
     *       系统通知 from_user_id = 0，照常计入。</li>
     *   <li>deleted_by_user_ids 为 JSON 数组：先去掉 {@code [ ] 空格} 再前后补逗号，用 LIKE 匹配实现
     *       跨 MySQL/H2 的成员排除。必须写 {@code CHAR(4000)}：H2 下裸 {@code CHAR} 是 CHAR(1)，
     *       只剩 {@code [}；MySQL 的 JSON→CHAR 形如 {@code [4, 5]}，直接补逗号无法命中首尾元素。</li>
     * </ul>
     */
    @Query(value = """
            SELECT COUNT(*) FROM chat_messages
            WHERE session_id = :sessionId AND create_time > :since AND status != 2
            AND from_user_id <> :userId
            AND CONCAT(',', REPLACE(REPLACE(REPLACE(COALESCE(CAST(deleted_by_user_ids AS CHAR(4000)), ''),
                '[', ''), ']', ''), ' ', ''), ',')
            NOT LIKE CONCAT('%,', :userId, ',%')
            """, nativeQuery = true)
    int countUnread(@Param("sessionId") Long sessionId,
                    @Param("since") long since,
                    @Param("userId") Long userId);
}

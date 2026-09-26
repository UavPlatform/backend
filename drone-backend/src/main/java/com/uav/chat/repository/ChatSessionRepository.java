package com.uav.chat.repository;

import com.uav.chat.pojo.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    List<ChatSession> findByTypeAndOwnerIdOrderByIdAsc(Integer type, Long ownerId);

    /** 按任务编号聚合任务会话（V4 idx_chat_session_task_num；task_num 可空，存量会话不命中）。 */
    List<ChatSession> findByTaskNum(String taskNum);
}

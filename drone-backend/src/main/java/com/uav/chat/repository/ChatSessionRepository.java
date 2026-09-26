package com.uav.chat.repository;

import com.uav.chat.pojo.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    List<ChatSession> findByTypeAndOwnerIdOrderByIdAsc(Integer type, Long ownerId);
}

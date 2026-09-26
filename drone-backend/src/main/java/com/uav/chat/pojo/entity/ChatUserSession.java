package com.uav.chat.pojo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "chat_user_session", uniqueConstraints = {
        @UniqueConstraint(name = "uk_session_user", columnNames = {"session_id", "user_id"})
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "join_time", nullable = false)
    private Long joinTime;

    @Column(name = "last_read_time")
    private Long lastReadTime;
}

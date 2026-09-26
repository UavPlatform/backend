package com.uav.chat.pojo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "chat_messages")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "msg_id", nullable = false, unique = true, length = 64)
    private String msgId;

    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Builder.Default
    @Column(name = "status", nullable = false)
    private Integer status = 0;

    @Column(name = "recall_time")
    private Long recallTime;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deleted_by_user_ids")
    private List<Long> deletedByUserIds = new ArrayList<>();

    @Builder.Default
    @Column(name = "create_time", nullable = false)
    private Long createTime = System.currentTimeMillis();

    @Builder.Default
    @Column(name = "msg_type", nullable = false)
    private Integer msgType = 0;
}

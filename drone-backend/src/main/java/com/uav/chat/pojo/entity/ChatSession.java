package com.uav.chat.pojo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Data
@Entity
@Table(name = "chat_session")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "type", nullable = false)
    private Integer type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_ids")
    private List<Long> userIds;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /**
     * 绑定的吊运任务编号（V4__chat_session_task.sql，ADR-0003 决定 1）。
     * 可空：存量会话与非任务会话为 {@code null}；任务内嵌会话写入对应 {@code task.num}。
     */
    @Column(name = "task_num", length = 64)
    private String taskNum;

    /**
     * 关联的应征记录 ID（ADR-0003「及可选 applicationId」）。
     * 创建任务会话时（任务属主 ↔ 应征飞手）应征记录存在则带上，可空。
     */
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "avatar", length = 500)
    private String avatar;

    @Column(name = "description", length = 500)
    private String description;

    @Builder.Default
    @Column(name = "create_time", nullable = false)
    private Long createTime = System.currentTimeMillis();
}

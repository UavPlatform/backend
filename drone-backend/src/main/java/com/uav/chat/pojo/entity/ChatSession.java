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

    @Column(name = "avatar", length = 500)
    private String avatar;

    @Column(name = "description", length = 500)
    private String description;

    @Builder.Default
    @Column(name = "create_time", nullable = false)
    private Long createTime = System.currentTimeMillis();
}

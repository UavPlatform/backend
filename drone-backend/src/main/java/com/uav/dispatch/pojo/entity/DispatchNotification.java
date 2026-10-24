package com.uav.dispatch.pojo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name = "dispatch_notification", uniqueConstraints = @UniqueConstraint(name = "uk_dispatch_notice_event", columnNames = {"recipient_id", "event_key"}))
@Getter @Setter
public class DispatchNotification {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long recipientId;
    @Column(nullable = false, length = 36) private String orderId;
    @Column(nullable = false, length = 128) private String eventKey;
    @Column(nullable = false, length = 16) private String audience;
    @Column(nullable = false, length = 32) private String kind;
    @Column(nullable = false, length = 100) private String title;
    @Column(nullable = false, length = 500) private String body;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime readAt;
}

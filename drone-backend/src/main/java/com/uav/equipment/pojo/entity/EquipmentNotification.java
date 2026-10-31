package com.uav.equipment.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** REQ-ALL-XC-005 minimal durable inbox for borrow events (push delivery still TODO). */
@Entity @Table(name = "equipment_notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_equipment_notice_event", columnNames = {"recipient_id", "event_key"}))
@Getter @Setter
public class EquipmentNotification {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long recipientId;
    @Column(nullable = false, length = 128) private String eventKey;
    @Column(nullable = false, length = 16) private String audience;
    @Column(nullable = false, length = 32) private String kind;
    @Column(nullable = false, length = 100) private String title;
    @Column(nullable = false, length = 500) private String body;
    @Column(length = 36) private String borrowId;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime readAt;
}

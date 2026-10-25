package com.uav.dispatch.pojo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** REQ-PILOT-MATCH-004: durable assignment epochs prevent stale commands after redispatch. */
@Entity
@Table(name = "dispatch_assignment", uniqueConstraints = @UniqueConstraint(columnNames = {"order_id", "assignment_version"}))
@Getter @Setter
public class DispatchAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 36) private String orderId;
    @Column(nullable = false) private long assignmentVersion;
    @Column(nullable = false) private Long pilotId;
    @Column(nullable = false) private long offerRevision;
    @Column(nullable = false) private LocalDateTime acceptedAt;
    @Column private LocalDateTime arrivedAt;
    @Column private LocalDateTime endedAt;
    @Column(length = 32) private String endReason;
}

package com.uav.flight.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
@Entity @Table(name = "dispatch_live_session") @Getter @Setter
public class DispatchLiveSession {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String orderId;
    @Column(nullable = false, length = 36) private String connectionId;
    @Column(nullable = false) private Long pilotId;
    @Column(nullable = false, length = 64) private String requestKey;
    @Column(nullable = false, length = 16) private String state;
    @Column(nullable = false) private LocalDateTime openedAt;
    private LocalDateTime endedAt;
    @Column(nullable = false) private long leaseExpiresAt;
    @Column(unique = true, length = 36) private String activeConnectionId;
}

package com.uav.equipment.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name = "drone_borrow", uniqueConstraints = @UniqueConstraint(name = "uk_pilot_borrow_request", columnNames = {"pilot_id", "request_key"}))
@Getter @Setter
public class DroneBorrow {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String droneId;
    @Column(nullable = false) private Long pilotId;
    @Column(nullable = false, length = 64) private String requestKey;
    @Column(nullable = false) private LocalDateTime borrowedAt;
    private LocalDateTime returnedAt;
}

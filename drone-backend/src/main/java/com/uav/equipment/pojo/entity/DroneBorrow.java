package com.uav.equipment.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** REQ-PILOT-EXEC-005 / REQ-SUP-GOV-007: application → approve(with SN) → loan; return clears custody. */
@Entity @Table(name = "drone_borrow", uniqueConstraints = @UniqueConstraint(name = "uk_pilot_borrow_request", columnNames = {"pilot_id", "request_key"}))
@Getter @Setter
public class DroneBorrow {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String RETURNED = "RETURNED";

    @Id @Column(length = 36) private String id;
    @Column(length = 36) private String droneId;
    @Column(nullable = false) private Long aircraftModelId;
    @Column(nullable = false) private Long pilotId;
    @Column(nullable = false, length = 64) private String requestKey;
    @Column(nullable = false, length = 16) private String status;
    @Column(length = 36) private String orderId;
    @Column(nullable = false) private LocalDateTime appliedAt;
    private LocalDateTime borrowedAt;
    private LocalDateTime returnedAt;
    @Column(length = 500) private String rejectReason;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
}

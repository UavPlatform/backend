package com.uav.dispatch.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
/** Latest opted-in fix only, no historical location trail. */
@Entity @Table(name = "dispatch_pilot_position") @Getter @Setter
public class DispatchPilotPosition {
    @Id private Long pilotId;
    @Column(nullable = false, length = 36) private String sessionKey;
    @Column private Double latitude;
    @Column private Double longitude;
    @Column private Double accuracyMeters;
    @Column private Long capturedAt;
    @Column(nullable = false) private long receivedAt;
    @Column(nullable = false) private long expiresAt;
}

package com.uav.flight.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
@Entity @Table(name = "dispatch_flight_sample") @Getter @Setter
public class FlightSample {
 @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
 @Column(nullable = false, length = 36) private String connectionId;
 @Column(nullable = false, length = 36) private String orderId;
 @Column(nullable = false, length = 64) private String serialNumber;
 @Column(nullable = false) private long sequenceNumber;
 @Column(nullable = false, length = 64) private String fingerprint;
 @Column(nullable = false) private long capturedAt;
 @Column(nullable = false) private LocalDateTime receivedAt;
 private Double latitude;
 private Double longitude;
 private Double altitude;
 private Double speed;
 private Integer battery;
 private Boolean flying;
 private Double payloadKg;
}
